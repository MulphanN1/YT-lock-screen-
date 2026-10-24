package com.bauman.pocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bauman.pocket.Data
import com.bauman.pocket.Lesson
import com.bauman.pocket.Plan
import com.bauman.pocket.Ru
import com.bauman.pocket.Semester
import java.time.LocalDateTime

fun LazyListScope.todayScreen(
    data: Data,
    at: LocalDateTime,
    onSettings: () -> Unit,
    onAllDeadlines: () -> Unit,
) {
    val today = at.toLocalDate()
    val week = Semester.weekOf(today)

    item("header") {
        val p = LocalPalette.current
        val holiday = Semester.HOLIDAYS[today]
        ScreenHeader(
            kicker = "${Ru.capitalize(Ru.dow(today))}, ${Ru.dayMonth(today)}",
            title = if (Semester.inSemester(week)) Ru.capitalize(Semester.parityName(week)) else "Вне семестра",
            sub = when {
                holiday != null -> "Праздник: $holiday"
                week in 1..Semester.TEACHING_WEEKS -> "$week-я неделя из ${Semester.TEACHING_WEEKS}"
                Semester.inSemester(week) -> "$week-я неделя · зачётная"
                else -> null
            },
            subColor = if (holiday != null) p.hot else null,
        ) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Настройки", tint = p.ink2)
            }
        }
    }

    item("now") { NowBlock(data, at) }

    val focus = Plan.focusDay(data, at)
    val focusLessons = data.lessons(focus)
    item("lessons") {
        val todayLessons = data.lessons(today)
        Column {
            if (focus == today) {
                SectionLabel("Пары сегодня", "${Plan.real(todayLessons).size} ${Ru.plural(Plan.real(todayLessons).size.toLong(), "пара", "пары", "пар")}")
                DayLessons(focusLessons, at, isToday = true)
            } else {
                if (todayLessons.isNotEmpty()) {
                    SectionLabel("Сегодня", "пары закончились")
                }
                val label = when (focus) {
                    today.plusDays(1) -> "Завтра"
                    else -> Ru.capitalize(Ru.dow(focus))
                }
                val fw = Semester.weekOf(focus)
                SectionLabel(label, "${Ru.dayMonthShort(focus)} · ${Semester.parityName(fw)}")
                if (focusLessons.isEmpty()) Note("Пар нет") else DayLessons(focusLessons, null, isToday = false)
            }
        }
    }

    val b = Plan.buckets(data, at)
    val soon = b.upcoming.filter { it.start?.let { s -> s <= today.plusDays(7) } == true }
    item("deadlines-label") {
        val p = LocalPalette.current
        val overdue = b.overdue.size
        SectionLabel(
            "Сроки на неделю",
            if (overdue > 0) "не закрыто: $overdue" else null,
            trailingColor = p.hot,
        )
    }
    if (!data.personal) {
        item("connect") {
            val p = LocalPalette.current
            Note("Сроки и баллы появятся, когда подключишь репозиторий bmstu-study. Открыть настройки →", p.accent, onSettings)
        }
    } else if (soon.isEmpty() && b.overdue.isEmpty()) {
        item("free") { Note("На ближайшие 7 дней сроков нет") }
    } else {
        b.overdue.take(3).forEachIndexed { i, it ->
            item("o-$i-${it.key}") {
                if (i > 0) Hairline(Modifier.padding(horizontal = Gutter))
                ItemRow(it, at, overdue = true)
            }
        }
        soon.take(5).forEachIndexed { i, it ->
            item("s-$i-${it.key}") {
                if (i > 0 || b.overdue.isNotEmpty()) Hairline(Modifier.padding(horizontal = Gutter))
                ItemRow(it, at)
            }
        }
        item("all") {
            val p = LocalPalette.current
            Note("Все сроки →", p.accent, onAllDeadlines)
        }
    }

    val marks = data.lks[week].orEmpty()
    if (marks.isNotEmpty()) {
        item("lks") {
            val p = LocalPalette.current
            Column {
                SectionLabel("Сетка ЛКС на этой неделе")
                Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                    marks.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Semester.subject(m.slug), style = Type.body, color = p.ink, modifier = Modifier.weight(1f))
                            Text(markName(m.mark), style = Type.smallStrong, color = if (m.hot) p.hot else p.ink2)
                        }
                    }
                }
            }
        }
    }

    item("footer") {
        val p = LocalPalette.current
        Spacer(Modifier.height(16.dp))
        if (data.syncedAt > 0) {
            val t = java.time.Instant.ofEpochMilli(data.syncedAt).atZone(Semester.ZONE).toLocalDateTime()
            Text(
                "Обновлено ${Ru.ddmm(t.toLocalDate())} в ${Ru.hm(t.toLocalTime())} · потяни вниз, чтобы обновить",
                style = Type.small, color = p.ink3, modifier = Modifier.padding(horizontal = Gutter),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** «РК» → «рубежный контроль» и т. п.: в сетке ЛКС одни сокращения. */
fun markName(m: String): String = m.split(" ").joinToString(" ") {
    when (it) {
        "РК" -> "рубежный контроль"
        "ДЗ" -> "домашнее задание"
        "КР" -> "контрольная"
        "М" -> "модуль"
        else -> it
    }
}

@Composable
private fun NowBlock(data: Data, at: LocalDateTime) {
    when (val s = Plan.now(data, at)) {
        is Plan.Now.During -> BigLesson("Сейчас", "ещё ${Ru.duration(s.left)}", s.lesson, s.progress)
        is Plan.Now.Next -> {
            if (s.date != at.toLocalDate()) return
            val label = if (s.inMinutes <= 60) "через ${Ru.duration(s.inMinutes)}" else "в ${Ru.hm(s.lesson.start!!)}"
            BigLesson("Дальше", label, s.lesson, null)
        }
        Plan.Now.Nothing -> Unit
    }
}

@Composable
private fun BigLesson(label: String, trailing: String, l: Lesson, progress: Float?) {
    val p = LocalPalette.current
    Column {
        SectionLabel(label, trailing, trailingColor = p.accent)
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(l.title, style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
                if (l.room != null) Text(l.room, style = Type.title, color = p.accent)
            }
            val meta = listOfNotNull(l.kind, l.teacher, l.note).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, style = Type.body, color = p.ink2)
            }
            Text(
                "${l.start?.let(Ru::hm).orEmpty()}–${l.end?.let(Ru::hm).orEmpty()}",
                style = Type.small, color = p.ink3, modifier = Modifier.padding(top = 2.dp),
            )
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                ProgressLine(progress)
            }
        }
    }
}
