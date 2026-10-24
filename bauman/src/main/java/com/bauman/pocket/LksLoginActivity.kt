package com.bauman.pocket

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Вход в ЛКС обычной страницей сайта. Закрывается сам, как только данные успеваемости загрузились. */
class LksLoginActivity : ComponentActivity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ink = ContextCompat.getColor(this, R.color.ink)
        val ink2 = ContextCompat.getColor(this, R.color.ink2)
        val dp = resources.displayMetrics.density

        val hint = TextView(this).apply {
            text = "Войди в ЛКС как обычно и открой «Успеваемость». Логин и пароль вводятся на сайте МГТУ, " +
                "приложение их не видит. Экран закроется сам, когда баллы загрузятся."
            setTextColor(ink2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val close = TextView(this).apply {
            text = "Закрыть"
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            addView(hint, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(close)
        }
        web = LksClient.webView(this) {
            Toast.makeText(this, "ЛКС подключён", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@LksLoginActivity, R.color.bg))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl(LksClient.SITE)
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
