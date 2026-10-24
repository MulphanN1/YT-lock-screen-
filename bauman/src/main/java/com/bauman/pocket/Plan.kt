package com.bauman.pocket

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Что происходит сейчас и что дальше. Общая логика для экрана, виджета и уведомлений. */
object Plan {
    /** За сколько до пары она становится «следующей» в уведомлении. */
    const val LEAD_MIN = 15L

    sealed interface Now {
        /** Идёт пара. */
        data class During(val lesson: Lesson, val date: LocalDate, val left: Long, val progress: Float) : Now

        /** Следующая пара сегодня или в ближайший учебный день. */
        data class Next(val lesson: Lesson, val date: LocalDate, val inMinutes: Long) : Now

        data object Nothing : Now
    }

    fun real(lessons: List<Lesson>) = lessons.filter { it.timed && !it.selfStudy }

    /** Минуты с округлением вверх: за 40 секунд до конца пары осталась «1 мин», а не «0». */
    private fun ceilMinutes(d: Duration) = (d.seconds + 59) / 60

    fun now(data: Data, at: LocalDateTime): Now {
        val today = at.toLocalDate()
        val t = at.toLocalTime()
        for (l in real(data.lessons(today))) {
            val s = l.start!!
            val e = l.end!!
            if (t >= s && t < e) {
                val total = Duration.between(s, e).toMinutes().coerceAtLeast(1)
                val passed = Duration.between(s, t).toMinutes()
                return Now.During(l, today, ceilMinutes(Duration.between(t, e)), passed.toFloat() / total)
            }
            if (t < s) return Now.Next(l, today, ceilMinutes(Duration.between(t, s)))
        }
        var d = today
        repeat(14) {
            d = Semester.nextStudyDay(d)
            val first = real(data.lessons(d)).firstOrNull()
            if (first != null) return Now.Next(first, d, ceilMinutes(Duration.between(at, d.atTime(first.start!!))))
        }
        return Now.Nothing
    }

    /** День, пары которого показывать на главном: сегодня, пока пары не кончились, иначе следующий учебный. */
    fun focusDay(data: Data, at: LocalDateTime): LocalDate {
        val today = at.toLocalDate()
        val last = real(data.lessons(today)).lastOrNull()?.end
        if (last != null && at.toLocalTime() < last) return today
        var d = today
        repeat(14) {
            d = Semester.nextStudyDay(d)
            if (data.lessons(d).isNotEmpty()) return d
        }
        return Semester.nextStudyDay(today)
    }

    // ---------------------------------------------------------------- сроки

    data class Buckets(
        val overdue: List<Item>,
        val upcoming: List<Item>,
        val waiting: List<Item>,
        val undated: List<Item>,
    )

    private val order = compareBy<Item>({ it.due ?: LocalDateTime.MAX }, { it.time ?: LocalTime.MAX })

    fun buckets(data: Data, at: LocalDateTime): Buckets {
        val open = data.open
        return Buckets(
            overdue = open.filter { !it.waiting && it.isPast(at) }.sortedWith(order),
            upcoming = open.filter { !it.waiting && it.dated && !it.isPast(at) }.sortedWith(order),
            waiting = open.filter { it.waiting }.sortedWith(order),
            undated = open.filter { !it.waiting && !it.dated },
        )
    }

    /** «сегодня в 17:35», «завтра», «через 4 дня», «на этой неделе», «через 2 недели». */
    fun until(item: Item, at: LocalDateTime): String {
        val today = at.toLocalDate()
        if (item.date != null) {
            val n = ChronoUnit.DAYS.between(today, item.date)
            val time = item.time?.let { " в ${Ru.hm(it)}" }.orEmpty()
            return when {
                n < 0 -> "${Ru.days(-n)} назад"
                n == 0L -> "сегодня$time"
                n == 1L -> "завтра$time"
                n == 2L -> "послезавтра"
                else -> "через ${Ru.days(n)}"
            }
        }
        val w = item.week ?: return ""
        val cur = Semester.weekOf(today)
        return when {
            w < cur -> "неделя прошла"
            w == cur -> "на этой неделе"
            w == cur + 1 -> "на следующей неделе"
            else -> "через ${w - cur} ${Ru.plural((w - cur).toLong(), "неделю", "недели", "недель")}"
        }
    }

    /** До срока меньше суток или он сегодня/завтра. */
    fun urgent(item: Item, at: LocalDateTime): Boolean {
        val d = item.date ?: return item.week == Semester.weekOf(at.toLocalDate())
        return ChronoUnit.DAYS.between(at.toLocalDate(), d) <= 1
    }

    fun whenLabel(item: Item): String = when {
        item.date != null -> "${Ru.dowShort(item.date)}, ${Ru.dayMonthShort(item.date)}" +
            (item.time?.let { ", ${Ru.hm(it)}" } ?: "")
        item.week != null -> "неделя ${item.week}"
        else -> item.raw.ifEmpty { "без срока" }
    }

    // ---------------------------------------------------------------- напоминания

    data class Reminder(val at: LocalDateTime, val item: Item, val title: String) {
        val id get() = (item.key + at).hashCode()
    }

    private val EVENING = LocalTime.of(20, 0)
    private val MORNING = LocalTime.of(7, 30)

    /**
     * Накануне в 20:00 и в сам день: утром, а если у срока есть время — за 2 часа
     * (но не раньше 7:30). Срок «на неделе N» — в понедельник утром.
     */
    fun reminders(data: Data): List<Reminder> {
        val out = mutableListOf<Reminder>()
        for (item in data.open) {
            if (item.waiting) continue
            val name = Semester.subject(item.subj)
            val date = item.date
            val time = item.time
            val week = item.week
            when {
                date != null -> {
                    out += Reminder(date.minusDays(1).atTime(EVENING), item, "Завтра · $name")
                    val sameDay = if (time != null) date.atTime(time).minusHours(2) else date.atTime(MORNING)
                    if (sameDay.toLocalTime() >= MORNING) {
                        out += Reminder(sameDay, item, if (time != null) "В ${Ru.hm(time)} · $name" else "Сегодня · $name")
                    }
                }
                week != null -> out += Reminder(Semester.monday(week).atTime(MORNING), item, "На этой неделе · $name")
            }
        }
        return out.sortedBy { it.at }
    }

    /** Ближайший момент, когда что-то на экране или в уведомлениях должно поменяться. */
    fun nextChange(data: Data, at: LocalDateTime): LocalDateTime {
        val candidates = mutableListOf(at.toLocalDate().plusDays(1).atTime(0, 1))
        var d = at.toLocalDate()
        repeat(2) {
            for (l in real(data.lessons(d))) {
                val s = d.atTime(l.start!!)
                candidates += listOf(s.minusMinutes(LEAD_MIN), s, d.atTime(l.end!!))
            }
            d = d.plusDays(1)
        }
        reminders(data).firstOrNull { it.at > at }?.let { candidates += it.at }
        return candidates.filter { it > at }.minOrNull() ?: at.plusHours(1)
    }
}
