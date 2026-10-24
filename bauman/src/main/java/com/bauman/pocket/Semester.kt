package com.bauman.pocket

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Календарь семестра. Те же константы, что в scripts/build_now.py репозитория bmstu-study:
 * неделя 1 (31.08–05.09.2026) — числитель, дальше чередуется.
 */
object Semester {
    val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    val START: LocalDate = LocalDate.of(2026, 8, 31)
    const val WEEKS = 19
    const val TEACHING_WEEKS = 17

    val HOLIDAYS: Map<LocalDate, String> = buildMap {
        put(LocalDate.of(2026, 11, 4), "День народного единства")
        put(LocalDate.of(2026, 12, 31), "выходной по переносу")
        for (d in 1..8) put(LocalDate.of(2027, 1, d), "новогодние каникулы")
    }

    val SUBJECTS = linkedMapOf(
        "matan" to "Матан",
        "analiticheskaya-geometriya" to "АГ",
        "informatika" to "Информатика",
        "upevm" to "УпЭВМ",
        "himiya" to "Химия",
        "nachertatelnaya-geometriya" to "Начерталка",
        "istoriya-rossii" to "История",
        "sotsiologiya" to "Социология",
        "inostranny-yazyk" to "Ин. яз",
        "fizkultura" to "ФКиС",
        "admin" to "Админка",
    )

    val LKS_NAMES = mapOf(
        "Аналитическая геометрия" to "analiticheskaya-geometriya",
        "Иностранный язык" to "inostranny-yazyk",
        "Информатика" to "informatika",
        "История России" to "istoriya-rossii",
        "Математический анализ" to "matan",
        "Начертательная геометрия" to "nachertatelnaya-geometriya",
        "Социология" to "sotsiologiya",
        "Учебный практикум на ЭВМ" to "upevm",
        "Физическая культура и спорт" to "fizkultura",
        "Химия" to "himiya",
    )

    fun now(): LocalDateTime = LocalDateTime.now(ZONE)

    fun subject(slug: String) = SUBJECTS[slug] ?: slug

    fun weekOf(d: LocalDate): Int = Math.floorDiv(ChronoUnit.DAYS.between(START, d), 7L).toInt() + 1

    fun monday(week: Int): LocalDate = START.plusWeeks((week - 1).toLong())

    fun isNumerator(week: Int) = Math.floorMod(week, 2) == 1

    fun parityName(week: Int) = if (isNumerator(week)) "числитель" else "знаменатель"

    fun inSemester(week: Int) = week in 1..WEEKS

    /** Ближайший день после [d], в который по идее есть пары: не воскресенье и не праздник. */
    fun nextStudyDay(d: LocalDate): LocalDate {
        var x = d.plusDays(1)
        while (x.dayOfWeek == DayOfWeek.SUNDAY || x in HOLIDAYS) x = x.plusDays(1)
        return x
    }
}

/** Русские подписи дат и чисел. */
object Ru {
    private val MONTHS_GEN = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val MONTHS_SHORT = listOf(
        "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
    )
    private val DOW_FULL = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val DOW_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val DOW_ACC = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")

    fun dow(d: LocalDate) = DOW_FULL[d.dayOfWeek.value - 1]
    fun dowShort(d: LocalDate) = DOW_SHORT[d.dayOfWeek.value - 1]

    /** «в понедельник», «в среду», «во вторник». */
    fun onDow(d: LocalDate): String {
        val w = DOW_ACC[d.dayOfWeek.value - 1]
        return if (d.dayOfWeek == DayOfWeek.TUESDAY) "во $w" else "в $w"
    }

    fun dayMonth(d: LocalDate) = "${d.dayOfMonth} ${MONTHS_GEN[d.monthValue - 1]}"
    fun dayMonthShort(d: LocalDate) = "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]}"
    fun monthShort(d: LocalDate) = MONTHS_SHORT[d.monthValue - 1]
    fun ddmm(d: LocalDate) = "%02d.%02d".format(d.dayOfMonth, d.monthValue)
    fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    fun plural(n: Long, one: String, few: String, many: String): String {
        val a = Math.abs(n) % 100
        val b = a % 10
        return when {
            a in 11..14 -> many
            b == 1L -> one
            b in 2..4 -> few
            else -> many
        }
    }

    fun days(n: Long) = "$n ${plural(n, "день", "дня", "дней")}"

    /** «23 мин», «1 ч 05 мин», «3 ч». */
    fun duration(minutes: Long): String {
        if (minutes < 60) return "$minutes мин"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h ч" else "$h ч %02d мин".format(m)
    }

    fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }
}
