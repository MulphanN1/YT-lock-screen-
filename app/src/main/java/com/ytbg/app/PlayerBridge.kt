package com.ytbg.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat

/** What the page's player is doing right now, as reported by [Scripts]. */
data class PlaybackInfo(
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** [SystemClock.elapsedRealtime] when [positionMs] was measured. */
    val updatedAt: Long = 0,
) {
    /** Where playback should be now if it kept going since the last report. */
    fun expectedPosition(now: Long = SystemClock.elapsedRealtime()): Long =
        if (playing) positionMs + (now - updatedAt) else positionMs

    /** True when only the position moved as expected, i.e. nothing worth redrawing. */
    fun sameTrackAndState(other: PlaybackInfo): Boolean =
        playing == other.playing &&
            title == other.title &&
            artist == other.artist &&
            artworkUrl == other.artworkUrl &&
            durationMs == other.durationMs &&
            kotlin.math.abs(other.expectedPosition(updatedAt) - positionMs) < POSITION_TOLERANCE_MS

    private companion object {
        const val POSITION_TOLERANCE_MS = 1500L
    }
}

/**
 * Connects the WebView player (owned by [MainActivity]) with [PlaybackService].
 * All listener callbacks run on the main thread.
 */
object PlayerBridge {

    interface Controller {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seekTo(positionMs: Long)
    }

    interface Listener {
        fun onPlaybackChanged(info: PlaybackInfo) {}
        fun onBackgroundModeChanged(enabled: Boolean) {}
    }

    var controller: Controller? = null

    var info = PlaybackInfo()
        private set

    /** Who paused playback last and when; shown on a long press of the power button. */
    var lastPause: String? = null
        private set

    private var lastCommand: String? = null
    private var lastCommandAt = 0L

    /** Pauses the player and remembers [source] so an unexpected pause can be traced. */
    fun pause(source: String) {
        lastCommand = source
        lastCommandAt = SystemClock.elapsedRealtime()
        controller?.pause()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Called from the JavaScript bridge thread. */
    fun reportPlayback(newInfo: PlaybackInfo) {
        main.post {
            if (newInfo.sameTrackAndState(info)) return@post
            if (info.playing && !newInfo.playing) {
                val recent = SystemClock.elapsedRealtime() - lastCommandAt < COMMAND_WINDOW_MS
                val source = if (recent) lastCommand else "сама страница YouTube"
                lastPause = "$source, ${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}"
            }
            info = newInfo
            listeners.toList().forEach { it.onPlaybackChanged(newInfo) }
        }
    }

    fun reportBackgroundMode(enabled: Boolean) {
        main.post { listeners.toList().forEach { it.onBackgroundModeChanged(enabled) } }
    }

    private const val COMMAND_WINDOW_MS = 2000L
}
