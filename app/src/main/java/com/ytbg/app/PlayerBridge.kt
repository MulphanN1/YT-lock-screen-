package com.ytbg.app

import android.os.Handler
import android.os.Looper

/**
 * Connects the WebView player (owned by [MainActivity]) with [PlaybackService].
 * All listener callbacks run on the main thread.
 */
object PlayerBridge {

    interface Controller {
        fun play()
        fun pause()
    }

    interface Listener {
        fun onPlaybackChanged(playing: Boolean, title: String?) {}
        fun onBackgroundModeChanged(enabled: Boolean) {}
    }

    var controller: Controller? = null

    var isPlaying = false
        private set
    var title: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Called from the JavaScript bridge thread. */
    fun reportPlayback(playing: Boolean, title: String?) {
        main.post {
            val cleanTitle = title?.takeIf { it.isNotBlank() }
            if (playing == isPlaying && cleanTitle == this.title) return@post
            isPlaying = playing
            this.title = cleanTitle
            listeners.toList().forEach { it.onPlaybackChanged(playing, cleanTitle) }
        }
    }

    fun reportBackgroundMode(enabled: Boolean) {
        main.post { listeners.toList().forEach { it.onBackgroundModeChanged(enabled) } }
    }
}
