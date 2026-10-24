package com.ytbg.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import org.json.JSONArray
import org.json.JSONException
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity(), PlayerBridge.Controller, PlayerBridge.Listener {

    private lateinit var webView: BackgroundWebView
    private lateinit var powerButton: View
    private lateinit var powerRing: View
    private lateinit var statusText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout

    private var enabled = false
    private var started = false
    private var injectOnPageLoad = true
    private var pageScript: ScriptHandler? = null

    // Read from the WebView network thread in shouldInterceptRequest.
    @Volatile
    private var adBlock = true
    private var colorAnimator: ValueAnimator? = null
    private var ringAnimator: ObjectAnimator? = null

    /** The page runs at minimum quality while nobody sees it (setting "Экономия в фоне"). */
    private var lowQualityActive = false

    /** JavaScript timers stopped because the app sat paused in the background. */
    private var suspended = false

    private var lastVideoId: String? = null
    private var lastSavedAt = 0L
    private var sponsorSettings: Pair<Boolean, Set<String>>? = null

    @Volatile
    private var lastBridgeReportAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val pollState = object : Runnable {
        override fun run() {
            if (!suspended) {
                // The page reports by itself; ask only if those reports stopped arriving.
                if (SystemClock.elapsedRealtime() - lastBridgeReportAt > BRIDGE_STALE_MS) {
                    webView.evaluateJavascript(Scripts.STATE) { result ->
                        parsePlaybackState(result)?.let(PlayerBridge::reportPlayback)
                    }
                }
                if (PlayerBridge.info.playing) savePlayback(PlayerBridge.info)
            }
            handler.postDelayed(this, STATE_POLL_MS)
        }
    }

    /** Paused in the background for a while: stop the heavy YouTube page from running. */
    private val suspendIdle = Runnable {
        if (!started && !PlayerBridge.info.playing && !suspended) {
            webView.onPause()
            webView.pauseTimers()
            suspended = true
        }
    }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        powerButton = findViewById(R.id.powerButton)
        powerRing = findViewById(R.id.powerRing)
        statusText = findViewById(R.id.statusText)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        progress = findViewById(R.id.progress)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        adBlock = Prefs.isAdBlockEnabled(this)
        setupWebView()
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        powerButton.setOnClickListener { toggle() }
        powerButton.setOnLongClickListener {
            val pause = PlayerBridge.lastPause?.let { getString(R.string.last_pause, it) }
                ?: getString(R.string.no_pauses)
            val message = pause + "\n" + getString(R.string.audio_status, PlaybackService.audioStatus)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            true
        }

        PlayerBridge.controller = this
        PlayerBridge.addListener(this)
        applyBackgroundMode(Prefs.isEnabled(this), animate = false)
        onPlaybackChanged(PlayerBridge.info)
        // Needed for the player notification on Android 13+.
        requestNotificationPermission()

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> chromeClient.onHideCustomView()
                webView.canGoBack() -> webView.goBack()
                // Keep the WebView (and the music) alive instead of finishing the activity.
                this@MainActivity.enabled -> moveTaskToBack(true)
                else -> finish()
            }
        }

        handler.post(pollState)

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(urlFromIntent(intent) ?: resumeUrl() ?: HOME_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Without the WebView markers YouTube serves the normal mobile site
            // and Google sign-in is less likely to be refused.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\S+ "), "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(JsBridge(), "YTBG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Keep the page's renderer from being killed first when memory gets low in the background.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        installPageScript()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleExternalUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (injectOnPageLoad) view.evaluateJavascript(Scripts.page(adBlock), null)
                // A fresh page has lost the skip segments: load them again for the next video.
                lastVideoId = null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Without this the whole app would crash together with the page.
                if (view === webView) handler.post { recreateWebView() }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (injectOnPageLoad) view.evaluateJavascript(Scripts.page(adBlock), null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (adBlock && AdBlock.isAd(request.url)) AdBlock.emptyResponse() else null
        }

        webView.webChromeClient = chromeClient
    }

    // Kept in a field: WebView.getWebChromeClient() only exists on API 26+.
    private val chromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.isVisible = newProgress < 100
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(view)
            fullscreenContainer.isVisible = true
            systemBars().hide(WindowInsetsCompat.Type.systemBars())
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.isVisible = false
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            systemBars().show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun installPageScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        pageScript?.remove()
        pageScript = WebViewCompat.addDocumentStartJavaScript(webView, Scripts.page(adBlock), setOf("*"))
        injectOnPageLoad = false
    }

    /** Replaces a WebView whose renderer died, continuing where the music was. */
    private fun recreateWebView() {
        val old = webView
        val parent = old.parent as ViewGroup
        val index = parent.indexOfChild(old)
        val params = old.layoutParams
        val wasPlaying = PlayerBridge.info.playing
        parent.removeView(old)
        old.destroy()
        pageScript = null
        injectOnPageLoad = true
        suspended = false
        webView = BackgroundWebView(this).apply { id = R.id.webView }
        parent.addView(webView, index, params)
        webView.keepPlayingInBackground = enabled
        setupWebView()
        // Only reopen the track if it was playing: opening a video starts it.
        webView.loadUrl((if (wasPlaying) resumeUrl() else null) ?: HOME_URL)
    }

    /** The last watch page with the position, or null when the setting is off. */
    private fun resumeUrl(): String? {
        if (!Prefs.isResumeEnabled(this)) return null
        val uri = Uri.parse(Prefs.lastPlaybackUrl(this) ?: return null)
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "t") continue
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        val seconds = Prefs.lastPlaybackPosition(this) / 1000
        if (seconds > 0) builder.appendQueryParameter("t", "${seconds}s")
        return builder.build().toString()
    }

    private fun savePlayback(info: PlaybackInfo, force: Boolean = false) {
        if (info.videoId == null || !Prefs.isResumeEnabled(this)) return
        val url = webView.url?.takeIf { "/watch" in it } ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSavedAt < SAVE_INTERVAL_MS) return
        lastSavedAt = now
        Prefs.saveLastPlayback(this, url, info.expectedPosition(now))
    }

    private fun loadSkipSegments(videoId: String) {
        val (on, categories) = sponsorSettings ?: return
        if (!on || !SponsorBlock.VIDEO_ID.matches(videoId)) return
        SponsorBlock.segments(videoId, categories) { segments ->
            if (!isDestroyed && segments.isNotEmpty() && PlayerBridge.info.videoId == videoId) {
                webView.evaluateJavascript(Scripts.segments(videoId, segments), null)
            }
        }
    }

    /** Picks up changes made on the settings screen. */
    private fun applySettings() {
        val newAdBlock = Prefs.isAdBlockEnabled(this)
        if (newAdBlock != adBlock) {
            adBlock = newAdBlock
            installPageScript()
            webView.reload()
        }
        val newSponsor = Prefs.isSponsorBlockEnabled(this) to Prefs.sponsorCategories(this)
        if (newSponsor != sponsorSettings) {
            sponsorSettings = newSponsor
            webView.evaluateJavascript(Scripts.segments("", emptyList()), null)
            lastVideoId = null
            PlayerBridge.info.videoId?.let { onVideoChanged(it) }
        }
    }

    private fun onVideoChanged(videoId: String) {
        lastVideoId = videoId
        loadSkipSegments(videoId)
        // A new video started while in the background: keep it at low quality too.
        if (lowQualityActive) webView.evaluateJavascript(Scripts.LOW_QUALITY, null)
    }

    private fun wake() {
        handler.removeCallbacks(suspendIdle)
        if (!suspended) return
        webView.resumeTimers()
        webView.onResume()
        suspended = false
    }

    private fun scheduleIdleSuspend() {
        handler.removeCallbacks(suspendIdle)
        if (!started && !PlayerBridge.info.playing) handler.postDelayed(suspendIdle, IDLE_SUSPEND_MS)
    }

    private fun systemBars(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

    /** Returns true when the URL must not be opened inside the WebView. */
    private fun handleExternalUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return true
        if (scheme != "http" && scheme != "https") {
            // intent://, vnd.youtube:// and friends would hand the video to the official app.
            return true
        }
        val host = uri.host ?: return false
        if (INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // No browser installed: just stay on the page.
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let { webView.loadUrl(it) }
    }

    private fun urlFromIntent(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { Regex("https?://\\S+").find(it)?.value }
        else -> null
    }

    private fun toggle() {
        val newState = !enabled
        Prefs.setEnabled(this, newState)
        applyBackgroundMode(newState, animate = true)
        Tiles.refresh(this)
        powerButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction {
            powerButton.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applyBackgroundMode(on: Boolean, animate: Boolean) {
        enabled = on
        webView.keepPlayingInBackground = on
        statusText.setText(if (on) R.string.mode_on else R.string.mode_off)

        val target = ContextCompat.getColor(this, if (on) R.color.power_on else R.color.power_off)
        colorAnimator?.cancel()
        if (animate) {
            val from = powerButton.backgroundTintList?.defaultColor ?: target
            colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
                duration = 250
                addUpdateListener {
                    powerButton.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int)
                }
                start()
            }
        } else {
            powerButton.backgroundTintList = ColorStateList.valueOf(target)
        }

        ringAnimator?.cancel()
        powerRing.alpha = 0f
        if (on) {
            ringAnimator = ObjectAnimator.ofPropertyValuesHolder(
                powerRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.4f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.4f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f),
            ).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        wake()
        webView.onResume()
        if (lowQualityActive) {
            webView.evaluateJavascript(Scripts.RESTORE_QUALITY, null)
            lowQualityActive = false
        }
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        // A start refused while the app was in the background is retried here.
        if (PlayerBridge.info.playing && !PlaybackService.isShowing) PlaybackService.start(this)
    }

    override fun onStop() {
        started = false
        savePlayback(PlayerBridge.info, force = true)
        if (!enabled) {
            pauseInBackground()
        } else if (Prefs.isLowQualityInBackground(this)) {
            // Also runs when the screen turns off: the activity stops then too.
            webView.evaluateJavascript(Scripts.LOW_QUALITY, null)
            lowQualityActive = true
        }
        scheduleIdleSuspend()
        super.onStop()
    }

    private fun pauseInBackground() {
        PlayerBridge.pause("приложение свёрнуто, фоновый режим выключен")
        webView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollState)
        handler.removeCallbacks(suspendIdle)
        PlayerBridge.removeListener(this)
        if (PlayerBridge.controller === this) PlayerBridge.controller = null
        PlaybackService.stop(this)
        ringAnimator?.cancel()
        colorAnimator?.cancel()
        webView.destroy()
        super.onDestroy()
    }

    // PlayerBridge.Controller

    override fun play() {
        wake()
        webView.evaluateJavascript(Scripts.PLAY, null)
    }

    override fun pause() {
        wake()
        webView.evaluateJavascript(Scripts.PAUSE, null)
    }

    override fun next() {
        wake()
        webView.evaluateJavascript(Scripts.NEXT, null)
    }

    override fun previous() {
        wake()
        webView.evaluateJavascript(Scripts.PREVIOUS, null)
    }

    // PlayerBridge.Listener

    override fun seekTo(positionMs: Long) {
        wake()
        webView.evaluateJavascript(Scripts.seekTo(positionMs), null)
    }

    override fun onPlaybackChanged(info: PlaybackInfo) {
        // The mobile site's page title is just "YouTube": not worth showing.
        val title = info.title?.takeUnless { it == "YouTube" }
        nowPlayingText.isVisible = title != null
        nowPlayingText.text = title?.let { getString(R.string.now_playing, it) }
        if (info.videoId != null && info.videoId != lastVideoId) onVideoChanged(info.videoId)
        if (!info.playing) {
            savePlayback(info, force = true)
            scheduleIdleSuspend()
        }
        // The player notification appears as soon as something plays, like in Spotify.
        if (info.playing && !PlaybackService.isShowing) PlaybackService.start(this)
    }

    override fun onBackgroundModeChanged(enabled: Boolean) {
        applyBackgroundMode(enabled, animate = true)
        if (!enabled && !started) pauseInBackground()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onState(json: String) {
            lastBridgeReportAt = SystemClock.elapsedRealtime()
            parsePlaybackState(json)?.let(PlayerBridge::reportPlayback)
        }
    }

    companion object {
        private const val HOME_URL = "https://m.youtube.com/"
        private const val STATE_POLL_MS = 5000L
        private const val BRIDGE_STALE_MS = 6000L
        private const val SAVE_INTERVAL_MS = 5000L
        private const val IDLE_SUSPEND_MS = 60_000L

        /** Parses the array built by the page script's state(); null when there is no player. */
        private fun parsePlaybackState(json: String?): PlaybackInfo? {
            val state = try {
                JSONArray(json ?: return null)
            } catch (e: JSONException) {
                return null
            }
            return PlaybackInfo(
                playing = state.optBoolean(0),
                title = state.optString(1).ifBlank { null },
                artist = state.optString(2).ifBlank { null },
                artworkUrl = state.optString(3).ifBlank { null },
                positionMs = state.optLong(4),
                durationMs = state.optLong(5),
                videoId = state.optString(6).ifBlank { null },
                updatedAt = SystemClock.elapsedRealtime(),
            )
        }

        private val INTERNAL_HOSTS = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "ytimg.com",
            "ggpht.com",
            "googlevideo.com",
            "google.com",
            "google.ru",
            "gstatic.com",
            "googleusercontent.com",
            "googleapis.com",
        )
    }
}
