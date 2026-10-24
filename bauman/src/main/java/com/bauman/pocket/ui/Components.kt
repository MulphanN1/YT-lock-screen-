package com.bauman.pocket.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bauman.pocket.Item
import com.bauman.pocket.Lesson
import com.bauman.pocket.Plan
import com.bauman.pocket.Ru
import com.bauman.pocket.Semester
import com.bauman.pocket.Source
import java.time.Duration
import java.time.LocalDateTime

val Gutter = 20.dp
private val TimeColumn = 58.dp

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalPalette.current.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Подпись раздела: тонкая линия и капсом мелко. */
@Composable
fun SectionLabel(text: String, trailing: String? = null, trailingColor: Color? = null, top: Dp = 28.dp) {
    val p = LocalPalette.current
    Column(Modifier.padding(start = Gutter, end = Gutter, top = top)) {
        Hairline(color = p.ink)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text.uppercase(), style = Type.label, color = p.ink, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = Type.small, color = trailingColor ?: p.ink2)
        }
    }
}

@Composable
fun ScreenHeader(kicker: String, title: String, sub: String? = null, subColor: Color? = null, action: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 8.dp, top = 20.dp)) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(kicker, style = Type.small, color = p.ink2)
            Spacer(Modifier.height(6.dp))
            Text(title, style = Type.display, color = p.ink)
            if (sub != null) {
                Spacer(Modifier.height(4.dp))
                Text(sub, style = Type.body, color = subColor ?: p.ink2)
            }
        }
        action()
    }
}

enum class RowState { PAST, NOW, LATER }

@Composable
fun LessonRow(lesson: Lesson, state: RowState, progress: Float = 0f, compact: Boolean = false) {
    val p = LocalPalette.current
    val dim = state == RowState.PAST || lesson.selfStudy
    val main = if (dim) p.ink3 else p.ink
    val second = if (dim) p.ink3 else p.ink2
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(if (state == RowState.NOW) p.accent else Color.Transparent),
            )
            Row(Modifier.weight(1f).padding(start = Gutter - 3.dp, end = Gutter, top = if (compact) 8.dp else 12.dp, bottom = if (compact) 8.dp else 12.dp)) {
                Column(Modifier.width(TimeColumn)) {
                    Text(lesson.start?.let(Ru::hm) ?: "—", style = Type.bodyStrong, color = if (state == RowState.NOW) p.accent else main)
                    if (!compact) Text(lesson.end?.let(Ru::hm).orEmpty(), style = Type.small, color = p.ink3)
                }
                Column(Modifier.weight(1f)) {
                    Text(lesson.title, style = Type.bodyStrong, color = main, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(lesson.kind, if (compact) null else lesson.teacher, lesson.note).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = Type.small, color = second, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (lesson.room != null) {
                    Text(
                        lesson.room, style = Type.smallStrong, color = main, textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 12.dp, top = 1.dp).width(72.dp),
                    )
                }
            }
        }
        if (state == RowState.NOW) ProgressLine(progress, Modifier.padding(horizontal = Gutter))
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = LocalPalette.current.accent, marker: Float? = null) {
    val p = LocalPalette.current
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val y = size.height / 2
        drawLine(p.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        val x = size.width * progress.coerceIn(0f, 1f)
        if (x > 0f) drawLine(color, Offset(0f, y), Offset(x, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        if (marker != null) {
            val mx = size.width * marker.coerceIn(0f, 1f)
            drawLine(p.ink, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/** Список пар дня с «окнами» между ними. */
@Composable
fun DayLessons(lessons: List<Lesson>, at: LocalDateTime?, isToday: Boolean, compact: Boolean = false) {
    val p = LocalPalette.current
    lessons.forEachIndexed { i, l ->
        if (i > 0) {
            val prevEnd = lessons[i - 1].end
            val gap = if (prevEnd != null && l.start != null) Duration.between(prevEnd, l.start).toMinutes() else 0
            if (gap >= 30) {
                Text(
                    "окно ${Ru.duration(gap)}", style = Type.small, color = p.ink3,
                    modifier = Modifier.padding(start = Gutter + TimeColumn, top = 2.dp, bottom = 2.dp),
                )
            } else {
                Hairline(Modifier.padding(start = Gutter + TimeColumn, end = Gutter))
            }
        }
        val t = at?.toLocalTime()
        val state = when {
            !isToday || t == null || l.start == null || l.end == null -> RowState.LATER
            t >= l.end!! -> RowState.PAST
            t >= l.start!! && !l.selfStudy -> RowState.NOW
            else -> RowState.LATER
        }
        val progress = if (state == RowState.NOW && t != null) {
            Duration.between(l.start!!, t).toMinutes().toFloat() / Duration.between(l.start, l.end!!).toMinutes().coerceAtLeast(1)
        } else 0f
        LessonRow(l, state, progress, compact)
    }
}

/** Строка срока. Нажатие раскрывает темы и статус целиком. */
@Composable
fun ItemRow(item: Item, at: LocalDateTime, overdue: Boolean = false, showWhen: Boolean = true) {
    val p = LocalPalette.current
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val urgent = overdue || Plan.urgent(item, at)
    Column(
        Modifier.fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null) { open = !open }
            .animateContentSize()
            .padding(horizontal = Gutter, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Semester.subject(item.subj).uppercase(), style = Type.label, color = p.ink2)
                    if (item.source == Source.RK) Text("КОНТРОЛЬ", style = Type.label, color = p.accent)
                }
                Spacer(Modifier.height(3.dp))
                Text(item.what, style = Type.bodyStrong, color = p.ink, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (showWhen) {
                Column(Modifier.padding(start = 16.dp).width(96.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        if (overdue) "срок прошёл" else Plan.until(item, at),
                        style = Type.smallStrong, color = if (urgent) p.hot else p.ink, textAlign = TextAlign.End,
                    )
                    Text(Plan.whenLabel(item), style = Type.small, color = p.ink3, textAlign = TextAlign.End)
                }
            }
        }
        if (open) {
            item.topics?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = Type.small, color = p.ink2)
            }
            item.max?.let {
                Spacer(Modifier.height(4.dp))
                Text("максимум $it б.", style = Type.small, color = p.ink2)
            }
        }
        if (item.statusText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                item.statusText, style = Type.small, color = p.ink3,
                maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Note(text: String, color: Color = LocalPalette.current.ink2, onClick: (() -> Unit)? = null) {
    val m = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Text(text, style = Type.small, color = color, modifier = m.padding(horizontal = Gutter, vertical = 10.dp))
}
