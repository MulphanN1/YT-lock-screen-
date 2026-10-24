package com.ytbg.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Spotify-style media notification and lock-screen / control-panel player for whatever
 * the WebView is playing. As a foreground service it also keeps the process (and the
 * WebView inside MainActivity) alive while the screen is off.
 */
class PlaybackService : Service(), PlayerBridge.Listener {

    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val main = Handler(Looper.getMainLooper())
    private val artworkLoader: ExecutorService = Executors.newSingleThreadExecutor()
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()

        session = MediaSessionCompat(this, "YtBg").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerBridge.controller?.play()
                }

                override fun onPause() {
                    PlayerBridge.controller?.pause()
                }

                override fun onSkipToNext() {
                    PlayerBridge.controller?.next()
                }

                override fun onSkipToPrevious() {
                    PlayerBridge.controller?.previous()
                }

                override fun onSeekTo(pos: Long) {
                    PlayerBridge.controller?.seekTo(pos)
                }

                override fun onStop() {
                    close()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytbg:playback").apply {
            setReferenceCounted(false)
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytbg:playback")
            .apply { setReferenceCounted(false) }

        PlayerBridge.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerBridge.controller?.play()
            ACTION_PAUSE -> PlayerBridge.controller?.pause()
            ACTION_NEXT -> PlayerBridge.controller?.next()
            ACTION_PREVIOUS -> PlayerBridge.controller?.previous()
            ACTION_CLOSE -> {
                close()
                return START_NOT_STICKY
            }
        }
        // Every start must promote the service to foreground, even if it is already running.
        render(PlayerBridge.info)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(PlayerBridge.info),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onPlaybackChanged(info: PlaybackInfo) {
        render(info)
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(info))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: the WebView is gone, nothing left to play.
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        PlayerBridge.removeListener(this)
        main.removeCallbacksAndMessages(null)
        artworkLoader.shutdownNow()
        session.isActive = false
        session.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /** The "×" button: pause and hide the player. It comes back on the next play. */
    private fun close() {
        PlayerBridge.controller?.pause()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun render(info: PlaybackInfo) {
        updateLocks(info.playing)
        loadArtwork(info.artworkUrl)
        session.setMetadata(metadata(info))
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (info.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    info.positionMs,
                    if (info.playing) 1f else 0f,
                    info.updatedAt,
                )
                .build(),
        )
    }

    private fun metadata(info: PlaybackInfo): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title(info))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist(info))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info.durationMs)
            .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
            .build()

    private fun updateLocks(playing: Boolean) {
        if (playing) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    /** Downloads the cover once per track and redraws the player when it arrives. */
    private fun loadArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artwork = null
        if (url == null) return
        artworkLoader.execute {
            val bitmap = download(url) ?: return@execute
            main.post {
                if (url != artworkUrl) return@post
                artwork = bitmap
                onPlaybackChanged(PlayerBridge.info)
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.inputStream.use { BitmapFactory.decodeStream(it) }?.let(::cropLetterbox)
    } catch (e: Exception) {
        null
    }

    /** YouTube's 4:3 thumbnails have black bars above and below a 16:9 picture. */
    private fun cropLetterbox(bitmap: Bitmap): Bitmap {
        if (bitmap.width * 3 != bitmap.height * 4) return bitmap
        val height = bitmap.width * 9 / 16
        return Bitmap.createBitmap(bitmap, 0, (bitmap.height - height) / 2, bitmap.width, height)
    }

    private fun buildNotification(info: PlaybackInfo): Notification {
        val playPause = if (info.playing) {
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, getString(R.string.action_play), serviceIntent(ACTION_PLAY))
        }
        val previous = NotificationCompat.Action(
            R.drawable.ic_skip_previous,
            getString(R.string.action_previous),
            serviceIntent(ACTION_PREVIOUS),
        )
        val next = NotificationCompat.Action(R.drawable.ic_skip_next, getString(R.string.action_next), serviceIntent(ACTION_NEXT))
        val close = NotificationCompat.Action(R.drawable.ic_close, getString(R.string.action_close), serviceIntent(ACTION_CLOSE))

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setLargeIcon(artwork)
            .setContentTitle(title(info))
            .setContentText(artist(info))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE))
            .setOngoing(info.playing)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(previous)
            .addAction(playPause)
            .addAction(next)
            .addAction(close)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun title(info: PlaybackInfo): String = info.title ?: getString(R.string.notif_title_default)

    private fun artist(info: PlaybackInfo): String = info.artist ?: "YouTube"

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        private const val ACTION_SHOW = "com.ytbg.app.SHOW"
        private const val ACTION_PLAY = "com.ytbg.app.PLAY"
        private const val ACTION_PAUSE = "com.ytbg.app.PAUSE"
        private const val ACTION_NEXT = "com.ytbg.app.NEXT"
        private const val ACTION_PREVIOUS = "com.ytbg.app.PREVIOUS"
        private const val ACTION_CLOSE = "com.ytbg.app.CLOSE"

        var isRunning = false
            private set

        /** Shows the player. Only allowed while the app is in the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java).setAction(ACTION_SHOW),
                )
            } catch (e: IllegalStateException) {
                // Started from the background on Android 12+: retried when the app is visible again.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
