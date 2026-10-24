package com.bauman.pocket

import org.json.JSONArray
import org.json.JSONObject

// Успеваемость из ЛКС (lks-back/api/v1/student/<id>/progress). Чистый разбор, без Android.

/** Контрольная точка: модуль, ДЗ, РК, КР, лаба или семинар. */
data class Ctl(
    val type: String,
    val week: Int,
    /** null — не выдано; 1 выдано; 2 на проверке; 3 защищено; 4 посещено; 12 работа на семинаре; 10/13 модуль выставлен. */
    val stage: String?,
    /** early | in time | late */
    val pass: String?,
    val value: Double?,
    val p3: Double?,
    val p4: Double?,
    val p5: Double?,
    val max: Double?,
    val setDate: String?,
) {
    val isModule get() = type == "М"
    val issued get() = stage == "1"
    val submitted get() = stage == "2"
    val defended get() = stage == "3"
    val attended get() = stage == "4" || stage == "12"
    val late get() = pass == "late"

    /** Оценка за модуль по порогам: 5, 4, 3 или 2 (ниже тройки). */
    val grade: Int?
        get() {
            val v = value ?: return null
            return when {
                p5 != null && v >= p5 -> 5
                p4 != null && v >= p4 -> 4
                p3 != null && v >= p3 -> 3
                else -> 2
            }
        }

    fun threshold(grade: Int) = when (grade) {
        3 -> p3
        4 -> p4
        5 -> p5
        else -> null
    }

    /** «03.10» из «2026-10-03 16:33:02». */
    val setDay: String?
        get() = setDate?.takeIf { it.length >= 10 }?.let { "${it.substring(8, 10)}.${it.substring(5, 7)}" }
}

data class Discipline(
    val title: String,
    val controls: List<Ctl>,
    val labs: List<Ctl>,
    val seminars: List<Ctl>,
) {
    val slug: String? get() = Semester.LKS_NAMES[title]
    val name: String get() = slug?.let(Semester::subject) ?: title
    val modules get() = controls.filter { it.isModule }.sortedBy { it.week }
    val tasks get() = controls.filter { !it.isModule }.sortedBy { it.week }
    val got get() = modules.sumOf { it.value ?: 0.0 }
    val total get() = modules.sumOf { it.max ?: 0.0 }

    /** Выдано и срок по ЛКС наступил, или сдано и ждёт проверки. */
    fun hanging(week: Int) = (tasks + labs).filter { (it.issued && it.week <= week) || it.submitted }
}

data class LksData(
    val week: Int?,
    val weekName: String?,
    /** Когда ЛКС отдал данные, «24.10 13:37». */
    val asOf: String?,
    val disciplines: List<Discipline>,
)

object LksParse {
    private fun str(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

    private fun num(o: JSONObject?, key: String): Double? =
        o?.let { str(it, key) }?.replace(',', '.')?.toDoubleOrNull()

    private fun ctls(a: JSONArray?): List<Ctl> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val points = o.optJSONObject("points")
            Ctl(
                type = str(o, "type").orEmpty(),
                week = str(o, "week")?.toIntOrNull() ?: 0,
                stage = str(o, "stage"),
                pass = str(o, "passStatus"),
                value = num(o, "value"),
                p3 = num(points, "point_3"),
                p4 = num(points, "point_4"),
                p5 = num(points, "point_5"),
                max = num(points, "point_all"),
                setDate = str(o, "setDate"),
            )
        }
    }

    private fun stamp(date: String?): String? =
        date?.takeIf { it.length >= 16 }?.let { "${it.substring(8, 10)}.${it.substring(5, 7)} ${it.substring(11, 16)}" }

    fun parse(progress: String, current: String?): LksData {
        val root = JSONObject(progress)
        val student = root.getJSONObject("data").getJSONObject("student")
        val list = student.optJSONArray("disciplines") ?: JSONArray()
        val disciplines = (0 until list.length()).mapNotNull { i ->
            val d = list.optJSONObject(i) ?: return@mapNotNull null
            Discipline(
                title = str(d, "title").orEmpty(),
                controls = ctls(d.optJSONArray("controls")),
                labs = ctls(d.optJSONArray("laboratory")).sortedBy { it.week },
                seminars = ctls(d.optJSONArray("seminars")).sortedBy { it.week },
            )
        }
        val cur = current?.let { runCatching { JSONObject(it).getJSONObject("data") }.getOrNull() }
        return LksData(
            week = cur?.let { str(it, "weekNumber")?.toIntOrNull() },
            weekName = cur?.let { str(it, "weekName") },
            asOf = stamp(str(root, "date")),
            disciplines = disciplines,
        )
    }
}
