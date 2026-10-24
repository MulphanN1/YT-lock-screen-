package com.ytbg.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * "Fake screen off": a black, touch-blocking overlay above every app. The real YouTube
 * app underneath believes the screen is on and keeps playing. Hold the screen to exit.
 */
object BlackScreen {

    private const val EXIT_HOLD_MS = 2000L
    private const val HINT_MS = 3000L
    private const val HINT_BRIGHTNESS = 0.2f

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var hint: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    val isShowing: Boolean
        get() = root != null

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (isShowing || !canShow(context)) return
        val app = context.applicationContext
        val windowManager = app.getSystemService(WindowManager::class.java)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // On LCD screens this dims the backlight as far as the system allows.
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        val text = TextView(app).apply {
            setText(R.string.black_hint)
            setTextColor(Color.GRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val view = FrameLayout(app).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            addView(
                text,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        hideSystemBars(view)

        val exit = Runnable { hide(app) }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showHint()
                    main.postDelayed(exit, EXIT_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(exit)
            }
            true
        }

        windowManager.addView(view, layoutParams)
        root = view
        hint = text
        params = layoutParams
        showHint()

        // A real screen-off (power button) pauses YouTube anyway: drop the overlay
        // so the user is not greeted by a black screen after unlocking.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                hide(context)
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffReceiver = receiver

        // Allowed from the background because the overlay is now visible.
        BlackScreenService.start(app)
        Tiles.refresh(app)
    }

    fun hide(context: Context) {
        val view = root ?: return
        val app = context.applicationContext
        main.removeCallbacksAndMessages(null)
        try {
            app.getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
        screenOffReceiver?.let { app.unregisterReceiver(it) }
        screenOffReceiver = null
        root = null
        hint = null
        params = null
        BlackScreenService.stop(app)
        Tiles.refresh(app)
    }

    private val hideHint = Runnable {
        hint?.animate()?.alpha(0f)?.setDuration(400)?.start()
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF)
    }

    private fun showHint() {
        main.removeCallbacks(hideHint)
        hint?.animate()?.alpha(1f)?.setDuration(150)?.start()
        setBrightness(HINT_BRIGHTNESS)
        main.postDelayed(hideHint, HINT_MS)
    }

    private fun setBrightness(value: Float) {
        val view = root ?: return
        val layoutParams = params ?: return
        if (layoutParams.screenBrightness == value) return
        layoutParams.screenBrightness = value
        view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, layoutParams)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(view: View) {
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                v.windowInsetsController?.hide(WindowInsets.Type.systemBars())
                insets
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
