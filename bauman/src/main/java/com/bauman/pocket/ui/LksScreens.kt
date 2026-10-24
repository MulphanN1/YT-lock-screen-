package com.bauman.pocket.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bauman.pocket.Ctl
import com.bauman.pocket.Discipline
import com.bauman.pocket.LksData
import com.bauman.pocket.Plan
import java.util.Locale

// Баллы и контрольные точки из ЛКС.

fun fmt(x: Double): String =
    if (x == Math.floor(x)) x.toLong().toString() else String.format(Locale("ru"), "%.1f", x)

fun LazyListScope.lksGrades(lks: LksData, week: Int) {
    item("header") {
        val set = lks.disciplines.sumOf { d -> d.modules.count { it.value != null && it.week <= week } }
        ScreenHeader(
            kicker = "из ЛКС" + (lks.asOf?.let { " · $it" } ?: ""),
            title = "Баллы",
            sub = "$week-я неделя · выставлено модулей: $set",
        )
    }
    lks.disciplines.forEach { d -> item("l-${d.title}") { DisciplineBlock(d, week) } }
    item("legend") {
        val p = LocalPalette.current
        Text(
            "Риски на полосе — пороги на «3», «4» и «5». Модуль, неделя которого ещё не пришла, копится: " +
                "его баллы не долг.",
            style = Type.small, color = p.ink3, modifier = Modifier.padding(horizontal = Gutter, vertical = 20.dp),
        )
    }
}

@Composable
private fun DisciplineBlock(d: Discipline, week: Int) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(d.name, if (d.total > 0) "${fmt(d.got)} из ${fmt(d.total)}" else null)
        d.modules.forEachIndexed { i, m -> ModuleRow(i + 1, m, week) }
        Column(Modifier.padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 8.dp)) {
            if (d.tasks.isNotEmpty()) Text(tasksLine(d.tasks, week, p), style = Type.small, color = p.ink2)
            labsLine(d.labs, week, p)?.let { Text(it, style = Type.small, color = p.ink2, modifier = Modifier.padding(top = 4.dp)) }
            seminarsLine(d.seminars, week)?.let { Text(it, style = Type.small, color = p.ink3, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun ModuleRow(n: Int, m: Ctl, week: Int) {
    val p = LocalPalette.current
    val v = m.value
    val due = m.week <= week
    val grade = if (due) m.grade else null
    val status = when {
        v != null && grade != null -> when {
            grade == 2 -> "ниже тройки: до «3» ${fmt((m.p3 ?: 0.0) - v)} б."
            grade < 5 -> m.threshold(grade + 1)?.let { "до «${grade + 1}» ${fmt(it - v)} б." } ?: "выставлен"
            else -> "максимальная оценка"
        }
        v != null -> "копится" + (m.p3?.takeIf { v < it }?.let { " · до «3» ${fmt(it - v)} б." } ?: "")
        due -> "ещё не выставлен"
        else -> m.p3?.let { "на «3» нужно ${fmt(it)}" } ?: "впереди"
    }
    val color = when {
        grade == 2 -> p.hot
        grade != null && grade >= 4 -> p.ok
        grade == 3 -> p.ink
        else -> p.accent
    }
    Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Модуль $n", style = Type.bodyStrong, color = p.ink)
                Text("неделя ${m.week} · $status", style = Type.small, color = if (grade == 2) p.hot else p.ink2)
            }
            Text(v?.let(::fmt) ?: "—", style = Type.title, color = if (v == null) p.ink3 else p.ink)
            Text(" / ${m.max?.let(::fmt) ?: "—"}", style = Type.body, color = p.ink2)
            if (grade != null) {
                Box(
                    Modifier.padding(start = 12.dp).size(34.dp).border(1.5.dp, color),
                    contentAlignment = Alignment.Center,
                ) { Text(grade.toString(), style = Type.heading, color = color) }
            }
        }
        if (m.max != null && m.max > 0) {
            Spacer(Modifier.height(6.dp))
            ModuleBar(m, if (due) color else p.accent)
        }
    }
}

/** Полоса модуля с рисками порогов и подписями «3», «4», «5» под ними. */
@Composable
private fun ModuleBar(m: Ctl, fill: Color) {
    val p = LocalPalette.current
    val max = m.max ?: return
    val ticks = listOfNotNull(m.p3?.let { 3 to it }, m.p4?.let { 4 to it }, m.p5?.let { 5 to it })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2
            val stroke = 4.dp.toPx()
            drawLine(p.line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Butt)
            val x = size.width * ((m.value ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
            if (x > 0f) drawLine(fill, Offset(0f, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Butt)
            ticks.forEach { (_, t) ->
                val tx = size.width * (t / max).toFloat().coerceIn(0f, 1f)
                drawLine(p.ink, Offset(tx, 0f), Offset(tx, size.height), strokeWidth = 1.5.dp.toPx())
            }
        }
        ticks.forEach { (g, t) ->
            val tx = w * (t / max).toFloat().coerceIn(0f, 1f)
            Text(
                g.toString(), style = Type.label, color = p.ink3,
                modifier = Modifier.offset(x = tx - 3.dp, y = 13.dp).width(12.dp),
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun ctlState(c: Ctl, week: Int): Pair<String, Boolean> = when {
    c.defended -> (if (c.late) "защищено с опозданием" else "защищено") to false
    c.submitted -> "на проверке" to false
    c.issued && c.week <= week -> "выдано, не сдано" to true
    c.issued -> "выдано" to false
    c.week <= week -> "не выставлено" to false
    else -> "впереди" to false
}

private fun tasksLine(tasks: List<Ctl>, week: Int, p: Palette): AnnotatedString = buildAnnotatedString {
    tasks.forEachIndexed { i, c ->
        if (i > 0) append("  ·  ")
        val (state, hot) = ctlState(c, week)
        withStyle(SpanStyle(color = if (hot) p.hot else p.ink)) { append("${c.type} ${c.week}") }
        withStyle(SpanStyle(color = if (hot) p.hot else p.ink2)) { append(" — $state") }
    }
}

private fun labsLine(labs: List<Ctl>, week: Int, p: Palette): AnnotatedString? {
    if (labs.isEmpty()) return null
    val hanging = labs.filter { it.issued && it.week <= week }
    val checking = labs.filter { it.submitted }
    return buildAnnotatedString {
        append("Лабы: защищено ${labs.count { it.defended }} из ${labs.size}")
        if (checking.isNotEmpty()) append(" · на проверке: нед. ${checking.joinToString(", ") { it.week.toString() }}")
        if (hanging.isNotEmpty()) {
            withStyle(SpanStyle(color = p.hot)) { append(" · висит: нед. ${hanging.joinToString(", ") { it.week.toString() }}") }
        }
    }
}

private fun seminarsLine(seminars: List<Ctl>, week: Int): String? {
    val due = seminars.filter { it.week <= week }
    if (due.isEmpty()) return null
    val work = due.count { it.stage == "12" }
    return "Семинары: отмечено ${due.count { it.attended }} из ${due.size}" + if (work > 0) ", работа на занятии — $work" else ""
}

/** Строки «висит в ЛКС» для вкладки «Сроки». */
fun LazyListScope.lksHanging(lks: LksData, week: Int) {
    val rows = lks.disciplines.flatMap { d -> d.hanging(week).map { d to it } }
    if (rows.isEmpty()) return
    item("lks-label") {
        val p = LocalPalette.current
        SectionLabel("Висит в ЛКС", rows.size.toString(), trailingColor = p.hot)
    }
    rows.forEachIndexed { i, (d, c) ->
        item("lks-$i") {
            val p = LocalPalette.current
            if (i > 0) Hairline(Modifier.padding(horizontal = Gutter))
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(d.name.uppercase(), style = Type.label, color = p.ink2)
                    Spacer(Modifier.height(3.dp))
                    Text(Plan.ctlName(c), style = Type.bodyStrong, color = p.ink)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (c.submitted) "на проверке" else "не сдано", style = Type.smallStrong, color = if (c.submitted) p.ink else p.hot)
                    c.setDay?.let { Text("выдано $it", style = Type.small, color = p.ink3) }
                }
            }
        }
    }
}
