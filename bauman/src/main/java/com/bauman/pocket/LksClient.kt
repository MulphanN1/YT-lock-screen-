package com.bauman.pocket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Забирает успеваемость из ЛКС. Логин и пароль приложение не видит: студент входит на lks.bmstu.ru
 * во встроенном браузере, а мы запоминаем то, чем ходит сам сайт, — куки и заголовок Authorization
 * его запросов к lks-back. С ними же потом ходим в API из фона.
 */
object LksClient {
    const val SITE = "https://lks.bmstu.ru/"
    private const val API = "https://lks.bmstu.ru/lks-back/api/v1/"
    private const val DEFAULT_STUDENT = "b631b9ca-7ba7-11f1-becf-0242ac110002"
    private val PROGRESS = Regex("""/lks-back/api/v1/student/([0-9a-fA-F-]{36})/progress""")

    class AuthError : Exception("Сессия ЛКС закончилась — войди заново")

    private fun prefs(ctx: Context) = Store.prefs(ctx)
    private fun dir(ctx: Context) = File(ctx.filesDir, "lks")
    fun progressFile(ctx: Context) = File(dir(ctx), "progress.json")
    fun currentFile(ctx: Context) = File(dir(ctx), "current.json")

    fun connected(ctx: Context) = prefs(ctx).getLong("lks_synced_at", 0) > 0
    fun syncedAt(ctx: Context) = prefs(ctx).getLong("lks_synced_at", 0)
    fun lastError(ctx: Context) = prefs(ctx).getString("lks_error", null)

    fun load(ctx: Context): LksData? {
        val p = progressFile(ctx)
        if (!p.exists()) return null
        val c = currentFile(ctx).takeIf { it.exists() }?.readText()
        return runCatching { LksParse.parse(p.readText(), c) }.getOrNull()
    }

    /** Подсмотреть запрос сайта к API: id студента и заголовок авторизации. */
    fun observe(ctx: Context, request: WebResourceRequest) {
        val url = request.url.toString()
        if ("/lks-back/" !in url) return
        val edit = prefs(ctx).edit()
        PROGRESS.find(url)?.let { edit.putString("lks_student", it.groupValues[1]) }
        request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.let {
            edit.putString("lks_auth", it.value)
        }
        edit.apply()
    }

    @Throws(AuthError::class, IOException::class)
    fun sync(ctx: Context) {
        val student = prefs(ctx).getString("lks_student", null) ?: DEFAULT_STUDENT
        val current = get(ctx, API + "schedules/current")
        val progress = get(ctx, API + "student/$student/progress")
        LksParse.parse(progress, current) // не сохраняем то, что не разбирается
        dir(ctx).mkdirs()
        currentFile(ctx).writeText(current)
        progressFile(ctx).writeText(progress)
        prefs(ctx).edit().putLong("lks_synced_at", System.currentTimeMillis()).remove("lks_error").apply()
    }

    /** null — успешно, иначе текст ошибки. [AuthError] отличаем: его лечит тихий вход через сайт. */
    fun syncCatching(ctx: Context): Exception? = try {
        sync(ctx)
        null
    } catch (e: AuthError) {
        prefs(ctx).edit().putString("lks_error", e.message).apply()
        e
    } catch (e: Exception) {
        prefs(ctx).edit().putString("lks_error", "ЛКС не ответил").apply()
        e
    }

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("lks_auth").remove("lks_student").remove("lks_synced_at").remove("lks_error").apply()
        progressFile(ctx).delete()
        currentFile(ctx).delete()
        CookieManager.getInstance().removeAllCookies(null)
    }

    private fun get(ctx: Context, url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("bauman-pocket"))
            // Из фоновой задачи WebView может быть недоступен — тогда идём только с заголовком.
            runCatching { CookieManager.getInstance().getCookie(SITE) }.getOrNull()?.let { conn.setRequestProperty("Cookie", it) }
            prefs(ctx).getString("lks_auth", null)?.let { conn.setRequestProperty("Authorization", it) }
            val code = conn.responseCode
            if (code == 401 || code == 403 || code in 300..399) throw AuthError()
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // Без сессии вместо JSON может прийти страница входа.
            if (!body.trimStart().startsWith("{")) throw AuthError()
            return body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * WebView, который открывает сайт ЛКС и после каждого запроса сайта к API пробует забрать данные.
     * [onSynced] зовётся на главном потоке один раз, когда получилось.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context, onSynced: () -> Unit): WebView {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        var done = false
        var running = false
        var lastTry = 0L
        fun attempt() {
            val now = System.currentTimeMillis()
            if (done || running || now - lastTry < 2_000) return
            running = true
            lastTry = now
            thread {
                val ok = syncCatching(app) == null
                main.post {
                    running = false
                    if (ok && !done) {
                        done = true
                        onSynced()
                    }
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    observe(app, request)
                    if ("/lks-back/api/" in request.url.toString()) main.postDelayed({ attempt() }, 1_500)
                    return null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    CookieManager.getInstance().flush()
                    if (url?.startsWith(SITE) == true) main.postDelayed({ attempt() }, 1_500)
                }
            }
        }
    }
}
