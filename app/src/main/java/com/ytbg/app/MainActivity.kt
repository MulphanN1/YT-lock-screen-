package com.ytbg.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
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
import androidx.annotation.RequiresApi
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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (BlackScreen.canShow(this)) showBlackScreenDialog()
        }

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
        setupChips()
        powerButton.setOnClickListener { toggle() }

        PlayerBridge.controller = this
        PlayerBridge.addListener(this)
        applyBackgroundMode(Prefs.isEnabled(this), animate = false)
        onPlaybackChanged(PlayerBridge.isPlaying, PlayerBridge.title)

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> chromeClient.onHideCustomView()
                webView.canGoBack() -> webView.goBack()
                // Keep the WebView (and the music) alive instead of finishing the activity.
                this@MainActivity.enabled -> moveTaskToBack(true)
                else -> finish()
            }
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(urlFromIntent(intent) ?: HOME_URL)
        }
        if (intent.getBooleanExtra(EXTRA_BLACK_SCREEN, false)) showBlackScreenDialog()
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

        installPageScript()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleExternalUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (injectOnPageLoad) view.evaluateJavascript(Scripts.page(adBlock), null)
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

    private fun setupChips() {
        findViewById<Chip>(R.id.chipBlack).setOnClickListener { showBlackScreenDialog() }
        findViewById<Chip>(R.id.chipTiles).setOnClickListener { addTiles() }
        findViewById<Chip>(R.id.chipAdBlock).apply {
            isChecked = adBlock
            setOnCheckedChangeListener { _, checked ->
                adBlock = checked
                Prefs.setAdBlockEnabled(this@MainActivity, checked)
                installPageScript()
                webView.reload()
                Toast.makeText(
                    this@MainActivity,
                    if (checked) R.string.ad_block_on else R.string.ad_block_off,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showBlackScreenDialog() {
        val allowed = BlackScreen.canShow(this)
        val message = getString(R.string.black_dialog_text) +
            if (allowed) "" else getString(R.string.black_dialog_no_permission)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.black_dialog_title)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
        if (allowed) {
            dialog.setPositiveButton(R.string.black_open_youtube) { _, _ -> openYouTubeAndDarken() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                dialog.setNeutralButton(R.string.black_add_tile) { _, _ ->
                    requestTile(BlackScreenTileService::class.java, R.string.tile_black, R.drawable.ic_dark) {}
                }
            }
        } else {
            dialog.setPositiveButton(R.string.black_allow) { _, _ ->
                overlayPermission.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
        }
        dialog.show()
    }

    private fun openYouTubeAndDarken() {
        val launch = packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, R.string.youtube_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launch)
        Toast.makeText(this, R.string.black_countdown, Toast.LENGTH_LONG).show()
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ BlackScreen.show(app) }, DARKEN_DELAY_MS)
    }

    private fun addTiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestTile(BackgroundTileService::class.java, R.string.tile_background, R.drawable.ic_power) {
                requestTile(BlackScreenTileService::class.java, R.string.tile_black, R.drawable.ic_dark) {}
            }
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tiles_dialog_title)
                .setMessage(R.string.tiles_dialog_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestTile(service: Class<*>, label: Int, icon: Int, then: () -> Unit) {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, service),
            getString(label),
            Icon.createWithResource(this, icon),
            mainExecutor,
        ) { then() }
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
        if (intent.getBooleanExtra(EXTRA_BLACK_SCREEN, false)) showBlackScreenDialog()
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
        if (newState) {
            requestNotificationPermission()
            PlaybackService.start(this)
        } else {
            PlaybackService.stop(this)
        }
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
        webView.onResume()
    }

    override fun onResume() {
        super.onResume()
        // Started here rather than in onCreate: a foreground service may only be
        // started while the activity is visible.
        if (enabled) PlaybackService.start(this)
    }

    override fun onStop() {
        started = false
        if (!enabled) pauseInBackground()
        super.onStop()
    }

    private fun pauseInBackground() {
        webView.evaluateJavascript(Scripts.PAUSE, null)
        webView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
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
        webView.evaluateJavascript(Scripts.PLAY, null)
    }

    override fun pause() {
        webView.evaluateJavascript(Scripts.PAUSE, null)
    }

    override fun next() {
        webView.evaluateJavascript(Scripts.NEXT, null)
    }

    override fun previous() {
        webView.evaluateJavascript(Scripts.PREVIOUS, null)
    }

    // PlayerBridge.Listener

    override fun onPlaybackChanged(playing: Boolean, title: String?) {
        nowPlayingText.isVisible = title != null
        nowPlayingText.text = title?.let { getString(R.string.now_playing, it) }
    }

    override fun onBackgroundModeChanged(enabled: Boolean) {
        applyBackgroundMode(enabled, animate = true)
        if (!enabled && !started) pauseInBackground()
    }

    private class JsBridge {
        @JavascriptInterface
        fun onState(playing: Boolean, title: String?) {
            PlayerBridge.reportPlayback(playing, title)
        }
    }

    companion object {
        const val EXTRA_BLACK_SCREEN = "black_screen"

        private const val HOME_URL = "https://m.youtube.com/"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val DARKEN_DELAY_MS = 10_000L

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
