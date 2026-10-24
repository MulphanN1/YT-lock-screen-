package com.ytbg.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * A WebView that can pretend to stay visible. When the screen turns off the window
 * becomes invisible and Chromium pauses media; reporting VISIBLE keeps it playing.
 */
class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    var keepPlayingInBackground = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepPlayingInBackground && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
