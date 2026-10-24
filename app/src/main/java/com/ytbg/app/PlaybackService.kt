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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Foreground service that keeps the process (and the WebView inside MainActivity)
 * alive while the screen is off, and shows lock-screen media controls.
 */
class PlaybackService : Service(), PlayerBridge.Listener {

    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSessionCompat(this, "YtBg").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerBridge.controller?.play()
                }

                override fun onPause() {
                    PlayerBridge.controller?.pause()
                }

                override fun onStop() {
                    turnOff()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }

        acquireLocks()
        PlayerBridge.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerBridge.controller?.play()
            ACTION_PAUSE -> PlayerBridge.controller?.pause()
            ACTION_STOP -> {
                turnOff()
                return START_NOT_STICKY
            }
        }
        // Every start must promote the service to foreground, even if it is already running.
        updateSession()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onPlaybackChanged(playing: Boolean, title: String?) {
        updateSession()
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: the WebView is gone, nothing left to keep alive.
        stopSelf()
    }

    override fun onDestroy() {
        PlayerBridge.removeListener(this)
        session.isActive = false
        session.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /** Switch the background mode off from the notification / lock screen. */
    private fun turnOff() {
        Prefs.setEnabled(this, false)
        PlayerBridge.reportBackgroundMode(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytbg:playback").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytbg:playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun updateSession() {
        val playing = PlayerBridge.isPlaying
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "YouTube")
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
    }

    private fun buildNotification(): Notification {
        val playing = PlayerBridge.isPlaying
        val playPause = if (playing) {
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, getString(R.string.action_play), serviceIntent(ACTION_PLAY))
        }
        val stop = NotificationCompat.Action(R.drawable.ic_close, getString(R.string.action_stop), serviceIntent(ACTION_STOP))

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(currentTitle())
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(playPause)
            .addAction(stop)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun currentTitle(): String = PlayerBridge.title ?: getString(R.string.notif_title_default)

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
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        private const val ACTION_START = "com.ytbg.app.START"
        private const val ACTION_PLAY = "com.ytbg.app.PLAY"
        private const val ACTION_PAUSE = "com.ytbg.app.PAUSE"
        private const val ACTION_STOP = "com.ytbg.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(ACTION_START),
            )
        }

        /** Stops the service without touching the saved toggle state. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
