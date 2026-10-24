package com.ytbg.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Spotify-style media player for whatever the WebView is playing: notification, lock
 * screen and the system / control-panel media card. As a foreground service it also keeps
 * the process (and the WebView inside MainActivity) alive while the screen is off.
 *
 * It is a [MediaBrowserServiceCompat] because that is how the system and launchers such as
 * Huawei's control panel recognise music players.
 */
class PlaybackService : MediaBrowserServiceCompat(), PlayerBridge.Listener {

    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val main = Handler(Looper.getMainLooper())
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()

    /** Cover of the current track; [artworkKey] is the video it belongs to. */
    private var artworkKey: String? = null
    private var artwork: Bitmap? = null

    /** Title and channel from YouTube's oEmbed API, per video id (main thread only). */
    private val tracks = HashMap<String, Track>()
    private var loadingTrack: String? = null

    private lateinit var audioManager: AudioManager
    private var hasFocus = false
    private var focusRequestedAt = 0L
    private var playStartedAt = 0L
    private var wasPlaying = false
    private var resumeOnFocusGain = false
    private val focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributesCompat.Builder()
                .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { onAudioFocusChange(it) }
        .build()

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()

        session = MediaSessionCompat(this, "YtBg").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerBridge.controller?.play()
                }

                override fun onPause() {
                    PlayerBridge.pause("система (команда паузы)")
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
                    close("система (команда стоп)")
                }
            })
            setSessionActivity(openAppIntent())
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            isActive = true
        }
        sessionToken = session.sessionToken

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytbg:playback").apply {
            setReferenceCounted(false)
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytbg:playback")
            .apply { setReferenceCounted(false) }

        PlayerBridge.addListener(this)
        render(PlayerBridge.info)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(BROWSER_ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // Nothing to browse: the music lives in the app's YouTube page.
        result.sendResult(mutableListOf())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerBridge.controller?.play()
            ACTION_PAUSE -> PlayerBridge.pause("кнопка в уведомлении")
            ACTION_NEXT -> PlayerBridge.controller?.next()
            ACTION_PREVIOUS -> PlayerBridge.controller?.previous()
            ACTION_CLOSE -> {
                close("кнопка «×» в уведомлении")
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                // Swiped away: only possible while paused, so there is nothing to pause.
                if (!PlayerBridge.info.playing) hide()
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
        isShowing = true
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onPlaybackChanged(info: PlaybackInfo) {
        render(info)
        if (isShowing && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(info))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: the WebView is gone, nothing left to play.
        hide()
    }

    override fun onDestroy() {
        isShowing = false
        PlayerBridge.removeListener(this)
        main.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        abandonAudioFocus()
        session.isActive = false
        session.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /** The "×" button: pause and hide the player. It comes back on the next play. */
    private fun close(source: String) {
        PlayerBridge.pause(source)
        abandonAudioFocus()
        hide()
    }

    private fun hide() {
        isShowing = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun render(info: PlaybackInfo) {
        updateLocks(info.playing)
        updateAudioFocus(info)
        loadTrack(info.videoId)
        loadArtwork(info)
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
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title(info))
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist(info))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info.durationMs)
            .apply {
                artwork?.let {
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                }
            }
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

    /**
     * Holding audio focus marks this app as the current player for the system media card.
     * Some WebView versions manage focus themselves; then taking it would pause the video,
     * so on the first sign of that we give up on audio focus for good and resume playback.
     */
    private fun updateAudioFocus(info: PlaybackInfo) {
        val now = SystemClock.elapsedRealtime()
        if (info.playing && !wasPlaying) playStartedAt = now
        if (!info.playing && wasPlaying && hasFocus && PlayerBridge.lastPauseByPage &&
            now - focusRequestedAt < FOCUS_GUARD_MS
        ) {
            focusConflict = true
            abandonAudioFocus()
            PlayerBridge.controller?.play()
        }
        wasPlaying = info.playing
        if (info.playing && !hasFocus && !focusConflict) {
            focusRequestedAt = now
            hasFocus = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocusGain) PlayerBridge.controller?.play()
                resumeOnFocusGain = false
            }
            // A call or a voice assistant: pause and continue afterwards.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (PlayerBridge.info.playing) {
                    resumeOnFocusGain = true
                    PlayerBridge.pause("звонок или голосовой помощник")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnFocusGain = false
                val lostAt = SystemClock.elapsedRealtime()
                main.postDelayed({
                    if (abs(playStartedAt - lostAt) < FOCUS_GUARD_MS) {
                        // Lost exactly when our own video started: that was the WebView.
                        focusConflict = true
                    } else if (PlayerBridge.info.playing) {
                        PlayerBridge.pause("другое приложение начало играть")
                    }
                }, FOCUS_GUARD_MS)
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume by itself.
        }
    }

    private fun abandonAudioFocus() {
        if (!hasFocus) return
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        hasFocus = false
    }

    /** Fetches the real title and channel once per video: the page title is just "YouTube". */
    private fun loadTrack(videoId: String?) {
        if (videoId == null || videoId in tracks || videoId == loadingTrack) return
        loadingTrack = videoId
        loader.execute {
            val track = fetchTrack(videoId)
            main.post {
                if (loadingTrack == videoId) loadingTrack = null
                tracks[videoId] = track
                if (track.title != null) onPlaybackChanged(PlayerBridge.info)
            }
        }
    }

    private fun fetchTrack(videoId: String): Track = try {
        val watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
        val connection = URL("https://www.youtube.com/oembed?format=json&url=$watchUrl").openConnection()
            as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        Track(
            title = json.optString("title").ifBlank { null },
            // Auto-generated music channels are called "Artist - Topic".
            artist = json.optString("author_name").removeSuffix(" - Topic").ifBlank { null },
        )
    } catch (e: Exception) {
        Track(null, null)
    }

    /** Downloads the cover once per video and redraws the player when it arrives. */
    private fun loadArtwork(info: PlaybackInfo) {
        val key = info.videoId ?: info.artworkUrl
        if (key == artworkKey) return
        artworkKey = key
        artwork = null
        if (key == null) return
        val candidates = info.videoId?.let { id ->
            listOf(
                "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            )
        } ?: listOfNotNull(info.artworkUrl)
        loader.execute {
            val bitmap = candidates.firstNotNullOfOrNull(::download) ?: return@execute
            main.post {
                if (key != artworkKey) return@post
                artwork = bitmap
                onPlaybackChanged(PlayerBridge.info)
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.inputStream.use { BitmapFactory.decodeStream(it) }?.let { prepareArtwork(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Removes the black bars of YouTube's 4:3 thumbnails and limits the size: the bitmap
     * travels to the system with every notification and session update.
     */
    private fun prepareArtwork(bitmap: Bitmap): Bitmap {
        var result = bitmap
        if (result.width * 3 == result.height * 4) {
            val height = result.width * 9 / 16
            result = Bitmap.createBitmap(result, 0, (result.height - height) / 2, result.width, height)
        }
        if (result.width > MAX_ARTWORK_WIDTH) {
            val height = result.height * MAX_ARTWORK_WIDTH / result.width
            result = Bitmap.createScaledBitmap(result, MAX_ARTWORK_WIDTH, height, true)
        }
        return result
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
            .setColor(ContextCompat.getColor(this, R.color.power_on))
            .setLargeIcon(artwork)
            .setContentTitle(title(info))
            .setContentText(artist(info))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_DISMISS))
            .setOngoing(info.playing)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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

    private fun title(info: PlaybackInfo): String =
        info.videoId?.let { tracks[it]?.title }
            ?: info.title?.takeUnless { it == "YouTube" }
            ?: getString(R.string.notif_title_default)

    private fun artist(info: PlaybackInfo): String =
        info.videoId?.let { tracks[it]?.artist } ?: info.artist ?: "YouTube"

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
        val manager = getSystemService(NotificationManager::class.java)
        // The old channel was "low importance", which Huawei folds into a collapsed group.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private data class Track(val title: String?, val artist: String?)

    companion object {
        private const val OLD_CHANNEL_ID = "playback"
        private const val CHANNEL_ID = "player"
        private const val NOTIFICATION_ID = 1
        private const val BROWSER_ROOT = "root"
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val FOCUS_GUARD_MS = 2000L
        private const val NETWORK_TIMEOUT_MS = 10_000
        private const val MAX_ARTWORK_WIDTH = 640

        private const val ACTION_SHOW = "com.ytbg.app.SHOW"
        private const val ACTION_PLAY = "com.ytbg.app.PLAY"
        private const val ACTION_PAUSE = "com.ytbg.app.PAUSE"
        private const val ACTION_NEXT = "com.ytbg.app.NEXT"
        private const val ACTION_PREVIOUS = "com.ytbg.app.PREVIOUS"
        private const val ACTION_CLOSE = "com.ytbg.app.CLOSE"
        private const val ACTION_DISMISS = "com.ytbg.app.DISMISS"

        /** True while the player notification is up (the service may also run just bound). */
        var isShowing = false
            private set

        /** Set once the WebView turns out to manage audio focus itself; lasts for the process. */
        private var focusConflict = false

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
