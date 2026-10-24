package com.bauman.pocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bauman.pocket.Data
import com.bauman.pocket.Ru
import com.bauman.pocket.Semester
import java.time.LocalDateTime

fun LazyListScope.weekScreen(data: Data, at: LocalDateTime, week: Int, onWeek: (Int) -> Unit) {
    val today = at.toLocalDate()
    val current = Semester.weekOf(today)
    val monday = Semester.monday(week)
    val saturday = monday.plusDays(5)

    item("header") {
        val p = LocalPalette.current
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                ScreenHeader(
                    kicker = when (week) {
                        current -> "эта неделя"
                        current + 1 -> "следующая неделя"
                        current - 1 -> "прошлая неделя"
                        else -> "${Ru.dayMonthShort(monday)} – ${Ru.dayMonthShort(saturday)}"
                    },
                    title = "$week-я неделя",
                    sub = "${Semester.parityName(week)} · ${monday.dayOfMonth}–${Ru.dayMonth(saturday)}",
                )
            }
            IconButton(onClick = { onWeek(week - 1) }, enabled = week > 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущая неделя", tint = if (week > 1) p.ink else p.ink3)
            }
            IconButton(onClick = { onWeek(week + 1) }, enabled = week < Semester.WEEKS) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующая неделя", tint = if (week < Semester.WEEKS) p.ink else p.ink3)
            }
        }
        if (week != current && Semester.inSemester(current)) {
            Note("Вернуться к текущей неделе", p.accent) { onWeek(current) }
        }
    }

    val marks = data.lksPlan[week].orEmpty()
    if (marks.isNotEmpty()) {
        item("lks") {
            val p = LocalPalette.current
            Column(Modifier.padding(start = Gutter, end = Gutter, top = 16.dp)) {
                Text("ПО СЕТКЕ ЛКС", style = Type.label, color = p.ink2)
                Spacer(Modifier.height(4.dp))
                Text(
                    marks.joinToString(" · ") { "${Semester.subject(it.slug)} — ${it.mark}" },
                    style = Type.small, color = p.ink,
                )
            }
        }
    }

    val open = data.open.filter { !it.waiting }
    val weekItems = open.filter { it.date == null && it.week == week }
    if (weekItems.isNotEmpty()) {
        item("week-items") {
            Column {
                SectionLabel("Сдать на этой неделе")
                weekItems.forEach { ItemRow(it, at, showWhen = false) }
            }
        }
    }

    for (i in 0 until 6) {
        val d = monday.plusDays(i.toLong())
        item("day-$i") {
            val p = LocalPalette.current
            val isToday = d == today
            val holiday = Semester.HOLIDAYS[d]
            val lessons = data.lessons(d)
            val due = open.filter { it.date == d }.sortedBy { it.time }
            Column {
                SectionLabel(
                    Ru.dow(d) + if (isToday) " · сегодня" else "",
                    Ru.dayMonthShort(d),
                    trailingColor = if (isToday) p.accent else null,
                )
                when {
                    holiday != null -> Note(Ru.capitalize(holiday), p.hot)
                    lessons.isEmpty() -> Note("Пар нет", p.ink3)
                    else -> DayLessons(lessons, if (isToday) at else null, isToday, compact = true)
                }
                due.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 6.dp)) {
                        Spacer(Modifier.width(3.dp).height(18.dp).background(p.hot))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Сдать${item.time?.let { " к ${Ru.hm(it)}" }.orEmpty()} · ${Semester.subject(item.subj)}",
                                style = Type.label, color = p.hot,
                            )
                            Text(item.what, style = Type.small, color = p.ink, maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    item("footer") {
        if (week == current) {
            val p = LocalPalette.current
            val nextWeek = data.lksPlan[week + 1].orEmpty()
            if (nextWeek.isNotEmpty()) {
                Note("На следующей неделе по ЛКС: " + nextWeek.joinToString(", ") { "${Semester.subject(it.slug)} — ${it.mark}" }, p.ink2)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
