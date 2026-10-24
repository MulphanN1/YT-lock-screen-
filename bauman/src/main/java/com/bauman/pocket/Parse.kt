package com.bauman.pocket

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Разбор markdown-файлов bmstu-study. Повторяет scripts/build_now.py: те же таблицы, те же шапки.

data class Lesson(
    val n: String,
    val start: LocalTime?,
    val end: LocalTime?,
    val title: String,
    /** лекция | семинар | лаба | null */
    val kind: String?,
    val teacher: String?,
    val room: String?,
    val note: String?,
) {
    val selfStudy get() = title.startsWith("Самостоятельная")
    val timed get() = start != null && end != null
}

enum class Source { DEADLINE, RK }

data class Item(
    val source: Source,
    val raw: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val week: Int?,
    val subj: String,
    val what: String,
    val topics: String?,
    val max: String?,
    val status: String,
) {
    val done get() = status.startsWith("✅")
    val waiting get() = status.startsWith("⏳")
    val dated get() = date != null || week != null

    /** Статус без значков ✅ / ⏳ в начале. */
    val statusText get() = status.removePrefix("✅").removePrefix("⏳").trim()

    /** Первый день, к которому относится срок. */
    val start: LocalDate? get() = date ?: week?.let { Semester.monday(it) }

    /** Последний момент срока: точное время, конец дня или суббота недели. */
    val due: LocalDateTime?
        get() = when {
            date != null -> date.atTime(time ?: LocalTime.of(23, 59))
            week != null -> Semester.monday(week).plusDays(5).atTime(23, 59)
            else -> null
        }

    val key get() = "$source|$raw|$subj|$what"

    fun isPast(now: LocalDateTime): Boolean {
        val d = due ?: return false
        return if (date != null && time == null) date < now.toLocalDate() else d < now
    }
}

data class Work(val name: String, val max: Double?, val got: String, val gotNum: Double?, val date: String)

data class Grade(
    val slug: String,
    val name: String,
    val got: Double,
    val max: Double,
    val graded: Int,
    val noMax: Double,
    val passes: Int,
    val semester: Int?,
    val threshold: Int?,
    val works: List<Work>,
) {
    val pct get() = if (max > 0) got / max else null

    /** Даже если дальше всё на максимум, порог не набрать. */
    val lost get() = semester != null && threshold != null && max > 0 && semester - (max - got) < threshold

    /** По оценённым работам идёшь ниже порога. */
    val low get() = !lost && semester != null && threshold != null && max > 0 && got / max < threshold.toDouble() / semester
}

data class LksMark(val slug: String, val mark: String, val hot: Boolean)

data class Data(
    val numerator: List<List<Lesson>>,
    val denominator: List<List<Lesson>>,
    val items: List<Item>,
    val lks: Map<Int, List<LksMark>>,
    val grades: List<Grade>,
    /** Сроки и баллы загружены из репозитория (без токена есть только расписание). */
    val personal: Boolean,
    val syncedAt: Long,
) {
    fun lessons(d: LocalDate): List<Lesson> {
        val dow = d.dayOfWeek.value - 1
        if (dow > 5 || d in Semester.HOLIDAYS) return emptyList()
        val grid = if (Semester.isNumerator(Semester.weekOf(d))) numerator else denominator
        return grid.getOrNull(dow).orEmpty()
    }

    val open get() = items.filter { !it.done }

    companion object {
        val EMPTY = Data(List(6) { emptyList() }, List(6) { emptyList() }, emptyList(), emptyMap(), emptyList(), false, 0)
    }
}

object Md {
    private val SEPARATOR = Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

    data class Table(val heading: String, val header: List<String>, val rows: List<List<String>>)

    fun clean(s: String) = s.replace("**", "").replace("`", "").trim()

    fun splitRow(line: String): List<String> {
        var l = line.trim().replace("\\|", "\u0000")
        if (l.startsWith("|")) l = l.substring(1)
        if (l.endsWith("|")) l = l.dropLast(1)
        return l.split("|").map { it.trim().replace("\u0000", "|") }
    }

    fun tables(text: String): List<Table> {
        val out = mutableListOf<Table>()
        var heading = ""
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val s = lines[i].trim()
            if (s.startsWith("#")) heading = s.trimStart('#').trim()
            if (s.startsWith("|") && i + 1 < lines.size && SEPARATOR.matches(lines[i + 1].trim())) {
                val header = splitRow(s).map(::clean)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += Table(heading, header, rows)
                continue
            }
            i++
        }
        return out
    }

    fun num(s: String): Double? {
        val m = Regex("""(\d+(?:[.,]\d+)?)""").matchEntire(clean(s)) ?: return null
        return m.groupValues[1].replace(',', '.').toDouble()
    }

    fun time(s: String?): LocalTime? {
        if (s == null) return null
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
    }

    private fun isEmpty(s: String) = s.isEmpty() || s == "—" || s == "-"

    // ---------------------------------------------------------------- сроки

    private val WHEN_DATE = Regex("""^(\d{4}-\d{2}-\d{2})(?:\s+(\d{1,2}:\d{2}))?""")
    private val WHEN_WEEK = Regex("""^неделя\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    fun items(text: String, source: Source): List<Item> {
        val out = mutableListOf<Item>()
        for (t in tables(text)) {
            if (t.header.firstOrNull() != "Когда") continue
            val col = HashMap<String, Int>()
            t.header.forEachIndexed { i, name -> if (name.isNotEmpty()) col[name.split(" ")[0]] = i }
            for (row in t.rows) {
                fun get(name: String): String = col[name]?.let { row.getOrNull(it) }?.trim().orEmpty()

                val raw = clean(get("Когда"))
                val topics = clean(get("Темы")).takeUnless(::isEmpty)
                val max = clean(get("Макс")).takeUnless(::isEmpty)
                var date: LocalDate? = null
                var time: LocalTime? = null
                var week: Int? = null
                val dm = WHEN_DATE.find(raw)
                val wm = WHEN_WEEK.find(raw)
                if (dm != null) {
                    date = runCatching { LocalDate.parse(dm.groupValues[1]) }.getOrNull()
                    time = time(dm.groupValues[2].ifEmpty { null })
                } else if (wm != null) {
                    week = wm.groupValues[1].toInt()
                }
                out += Item(source, raw, date, time, week, clean(get("Предмет")),
                    clean(get("Что")), topics, max, clean(get("Статус")))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- расписание

    private val TIME_IN_CELL = Regex("""\((\d{1,2}:\d{2})\s*[–-]\s*(\d{1,2}:\d{2})\)""")
    private val DOW_HEADER = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
    private val KINDS = mapOf("лек" to "лекция", "сем" to "семинар", "лаб" to "лаба")
    private val ROOM = Regex("""^(\d+[а-яa-z]?(\.\d+)?[а-яa-z]?|каф\..+)$""", RegexOption.IGNORE_CASE)

    /** Пара: «Матан лек · Велищанский · 324х», «ФКиС · Измайлово, начало 16:35». */
    fun lesson(n: String, start: LocalTime?, end: LocalTime?, cell: String): Lesson {
        val parts = cell.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        var title = parts.firstOrNull().orEmpty()
        var kind: String? = null
        val words = title.split(" ")
        if (words.size > 1 && words.last() in KINDS) {
            kind = KINDS[words.last()]
            title = words.dropLast(1).joinToString(" ")
        }
        var teacher: String? = null
        var room: String? = null
        var note: String? = null
        when {
            parts.size >= 3 -> {
                teacher = parts.subList(1, parts.size - 1).joinToString(" · ")
                room = parts.last()
            }
            parts.size == 2 -> if (ROOM.matches(parts[1])) room = parts[1] else note = parts[1]
        }
        return Lesson(n, start, end, title, kind, teacher, room, note)
    }

    /** Две сетки (числитель, знаменатель) по 6 дней. */
    fun schedule(text: String): Pair<List<List<Lesson>>, List<List<Lesson>>> {
        val grids = HashMap<Boolean, List<List<Lesson>>>()
        for (t in tables(text)) {
            val h = t.heading.uppercase()
            val numerator = when {
                h.startsWith("ЧИСЛИТЕЛЬ") -> true
                h.startsWith("ЗНАМЕНАТЕЛЬ") -> false
                else -> continue
            }
            if (t.header.take(2) != listOf("№", "Время") || t.header.drop(2).take(6) != DOW_HEADER) continue
            val days = List(6) { mutableListOf<Lesson>() }
            for (row in t.rows) {
                val n = clean(row.getOrElse(0) { "" })
                val rowTime = clean(row.getOrElse(1) { "" })
                for (di in 0 until 6) {
                    var c = clean(row.getOrElse(2 + di) { "" })
                    if (isEmpty(c)) continue
                    var range = rowTime
                    TIME_IN_CELL.find(c)?.let { m ->
                        range = "${m.groupValues[1]}–${m.groupValues[2]}"
                        c = (c.substring(0, m.range.first) + c.substring(m.range.last + 1)).trim()
                    }
                    val se = range.split('–', '-')
                    days[di] += lesson(n, time(se.getOrNull(0)), time(se.getOrNull(1)), c)
                }
            }
            grids[numerator] = days.map { d -> d.sortedBy { it.start ?: LocalTime.MAX } }
        }
        val empty = List(6) { emptyList<Lesson>() }
        return (grids[true] ?: empty) to (grids[false] ?: empty)
    }

    // ---------------------------------------------------------------- преподаватели

    /** Фамилия → «Пинчук И.А.» из таблицы `| Предмет | ФИО | … |` в contacts.md. */
    fun teachers(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (t in tables(text)) {
            val fi = t.header.indexOf("ФИО")
            if (fi < 0) continue
            for (row in t.rows) {
                val words = clean(row.getOrElse(fi) { "" }).split(" ").filter { it.isNotEmpty() }
                val surname = words.firstOrNull()?.takeIf { it.first().isLetter() } ?: continue
                val initials = words.drop(1).take(2).joinToString("") { "${it.first()}." }
                out[surname] = if (initials.isEmpty()) surname else "$surname $initials"
            }
        }
        return out
    }

    /** «Палий, Михеенкова» → «Палий Н.В., Михеенкова»; кого нет в контактах — как в расписании. */
    fun shortTeacher(raw: String, known: Map<String, String>): String =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { part ->
            known[part.substringBefore(" ")] ?: part
        }

    // ---------------------------------------------------------------- сетка ЛКС

    fun lks(text: String): Map<Int, List<LksMark>> {
        val out = HashMap<Int, MutableList<LksMark>>()
        for (t in tables(text)) {
            if (t.header.firstOrNull() != "Предмет") continue
            val weeks = t.header.drop(1).map { it.toIntOrNull() }
            if (weeks.any { it == null }) continue
            for (row in t.rows) {
                val name = clean(row.getOrElse(0) { "" })
                val slug = Semester.LKS_NAMES[name] ?: name
                weeks.forEachIndexed { i, w ->
                    val cell = clean(row.getOrElse(i + 1) { "" })
                    if (cell.isNotEmpty()) {
                        val hot = "🔴" in cell
                        val mark = cell.replace("🔴", "").replace("◎", "").replace("○", "").replace("●", "").trim()
                        if (mark.isNotEmpty()) out.getOrPut(w!!) { mutableListOf() } += LksMark(slug, mark, hot)
                    }
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- БРС

    fun grades(text: String): List<Grade> {
        val out = mutableListOf<Grade>()
        for (block in text.split(Regex("(?m)^## ")).drop(1)) {
            val heading = block.substringBefore("\n")
            val body = block.substringAfter("\n", "")
            val slug = Regex("""^([a-z-]+)\b""").find(heading)?.groupValues?.get(1) ?: continue
            val name = heading.substringAfter("—", Semester.subject(slug)).trim()
            var semester: Int? = null
            var threshold: Int? = null
            Regex("""(?m)^.*Семестр:.*$""").find(body)?.let { line ->
                semester = Regex("""максимум\s+(\d+)""").find(line.value)?.groupValues?.get(1)?.toInt()
                threshold = Regex("""порог\s+(\d+)""").find(line.value)?.groupValues?.get(1)?.toInt()
            }
            var got = 0.0
            var max = 0.0
            var noMax = 0.0
            var graded = 0
            var passes = 0
            val works = mutableListOf<Work>()
            for (t in tables(body)) {
                val gi = t.header.indexOf("Получено")
                val mi = t.header.indexOf("Макс")
                if (gi < 0 || mi < 0) continue
                val ni = t.header.indexOf("Работа").takeIf { it >= 0 } ?: 0
                val di = t.header.indexOf("Дата")
                for (row in t.rows) {
                    val g = clean(row.getOrElse(gi) { "" })
                    val mx = num(row.getOrElse(mi) { "" })
                    val gn = num(g)
                    if (gn != null) {
                        graded++
                        if (mx == null) noMax += gn else {
                            got += gn
                            max += mx
                        }
                    } else if (g.lowercase().let { it.startsWith("зачёт") || it.startsWith("зачет") || it.startsWith("принят") }) {
                        passes++
                    }
                    works += Work(clean(row.getOrElse(ni) { "" }), mx, g, gn, if (di >= 0) clean(row.getOrElse(di) { "" }) else "")
                }
            }
            out += Grade(slug, name, got, max, graded, noMax, passes, semester, threshold, works)
        }
        return out
    }
}
