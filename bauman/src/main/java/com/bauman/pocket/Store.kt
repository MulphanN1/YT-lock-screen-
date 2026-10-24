package com.bauman.pocket

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Данные лежат в приватном репозитории bmstu-study (его ведёт Claude). Приложение читает пять
 * markdown-файлов через GitHub API и хранит копию: без сети всё работает по последней загрузке.
 * Расписание и сетка ЛКС есть и в самом APK, так что пары видны даже без токена.
 */
object Store {
    const val DEFAULT_REPO = "MulphanN1/bmstu-study"

    private val FILES = listOf(
        "university/schedule.md",
        "university/deadlines.md",
        "university/rk-dates.md",
        "university/brs.md",
        "university/lks-plan.md",
        "university/contacts.md",
    )

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("bauman", Context.MODE_PRIVATE)

    fun repo(ctx: Context) = prefs(ctx).getString("repo", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO
    fun token(ctx: Context) = prefs(ctx).getString("token", null).orEmpty()
    fun syncedAt(ctx: Context) = prefs(ctx).getLong("synced_at", 0)
    fun lastError(ctx: Context) = prefs(ctx).getString("last_error", null)
    fun notifyNow(ctx: Context) = prefs(ctx).getBoolean("notify_now", true)
    fun notifyDeadlines(ctx: Context) = prefs(ctx).getBoolean("notify_deadlines", true)

    private fun dir(ctx: Context) = File(ctx.filesDir, "repo")
    private fun cached(ctx: Context, path: String) = File(dir(ctx), path.substringAfterLast('/'))

    private fun read(ctx: Context, path: String, asset: Boolean): String? {
        val f = cached(ctx, path)
        if (f.exists()) return f.readText()
        if (!asset) return null
        return runCatching { ctx.assets.open(path.substringAfterLast('/')).bufferedReader().use { it.readText() } }.getOrNull()
    }

    fun load(ctx: Context): Data {
        val (num, den) = Md.schedule(read(ctx, "university/schedule.md", asset = true).orEmpty())
        val deadlines = read(ctx, "university/deadlines.md", asset = false)
        val rk = read(ctx, "university/rk-dates.md", asset = false)
        val brs = read(ctx, "university/brs.md", asset = false)
        val lks = read(ctx, "university/lks-plan.md", asset = true).orEmpty()
        val teachers = Md.teachers(read(ctx, "university/contacts.md", asset = false).orEmpty())
        fun short(grid: List<List<Lesson>>) = grid.map { day ->
            day.map { l -> l.copy(teacher = l.teacher?.let { Md.shortTeacher(it, teachers) }) }
        }
        return Data(
            numerator = short(num),
            denominator = short(den),
            items = Md.items(deadlines.orEmpty(), Source.DEADLINE) + Md.items(rk.orEmpty(), Source.RK),
            lksPlan = Md.lks(lks),
            grades = Md.grades(brs.orEmpty()),
            personal = deadlines != null,
            syncedAt = syncedAt(ctx),
            lks = LksClient.load(ctx),
        )
    }

    class SyncError(message: String) : Exception(message)

    /** Скачать файлы. Бросает [SyncError] с понятным текстом. */
    @Throws(SyncError::class)
    fun sync(ctx: Context) {
        val token = token(ctx)
        if (token.isBlank()) throw SyncError("Нет токена GitHub — открой настройки")
        val repo = repo(ctx)
        val texts = FILES.associateWith { fetch(repo, it, token) }
        // Сначала всё скачали, потом записали: не бывает половины старых файлов и половины новых.
        dir(ctx).mkdirs()
        texts.forEach { (path, text) -> cached(ctx, path).writeText(text) }
        prefs(ctx).edit().putLong("synced_at", System.currentTimeMillis()).remove("last_error").apply()
    }

    fun syncCatching(ctx: Context): String? = try {
        sync(ctx)
        null
    } catch (e: SyncError) {
        prefs(ctx).edit().putString("last_error", e.message).apply()
        e.message
    }

    private fun fetch(repo: String, path: String, token: String): String {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val conn = try {
            URL("https://api.github.com/repos/$repo/contents/$encoded?ref=main").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw SyncError("Неверное имя репозитория: $repo")
        }
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github.raw+json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "bauman-pocket")
            val code = conn.responseCode
            when (code) {
                200 -> return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                401 -> throw SyncError("GitHub не принял токен: он неверный или истёк")
                403 -> throw SyncError("У токена нет доступа к $repo (или кончился лимит запросов)")
                404 -> throw SyncError("Не вижу $path в $repo: проверь имя репозитория и доступ токена")
                else -> throw SyncError("GitHub ответил $code")
            }
        } catch (e: IOException) {
            throw SyncError("Нет связи с GitHub")
        } finally {
            conn.disconnect()
        }
    }
}
