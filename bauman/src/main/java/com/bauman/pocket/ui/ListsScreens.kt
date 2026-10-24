package com.bauman.pocket.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bauman.pocket.Data
import com.bauman.pocket.Grade
import com.bauman.pocket.Item
import com.bauman.pocket.Plan
import com.bauman.pocket.Ru
import java.time.LocalDateTime
import java.util.Locale

// ---------------------------------------------------------------- сроки

fun LazyListScope.deadlinesScreen(data: Data, at: LocalDateTime, onSettings: () -> Unit) {
    val b = Plan.buckets(data, at)
    val total = b.overdue.size + b.upcoming.size + b.undated.size
    val waiting = if (b.waiting.isNotEmpty()) " · ждём результат: ${b.waiting.size}" else ""
    val sub = "$total ${Ru.plural(total.toLong(), "открытый", "открытых", "открытых")}$waiting"
    item("header") {
        ScreenHeader(kicker = "из deadlines.md и rk-dates.md", title = "Сроки", sub = if (data.personal) sub else null)
    }
    if (!data.personal) {
        item("connect") {
            val p = LocalPalette.current
            Spacer(Modifier.height(16.dp))
            Note("Сроки хранятся в репозитории bmstu-study. Подключи его в настройках →", p.accent, onSettings)
        }
        return
    }

    val asks = Plan.asks(data, at)
    if (asks.isNotEmpty()) item("claude") { ClaudeBlock(asks, at) }

    section("overdue", "Срок прошёл", "отметь у Claude, как прошло", b.overdue, at, overdue = true)
    section("upcoming", "Впереди", null, b.upcoming, at)
    section("waiting", "Ждём результат", null, b.waiting, at, showWhen = false)
    section("undated", "Без срока", null, b.undated, at, showWhen = false)
    item("footer") { Spacer(Modifier.height(24.dp)) }
}

private fun LazyListScope.section(
    key: String,
    title: String,
    hint: String?,
    items: List<Item>,
    at: LocalDateTime,
    overdue: Boolean = false,
    showWhen: Boolean = true,
) {
    if (items.isEmpty()) return
    item("$key-label") {
        val p = LocalPalette.current
        SectionLabel(title, hint ?: items.size.toString(), trailingColor = if (overdue) p.hot else null)
    }
    items.forEachIndexed { i, it ->
        item("$key-$i-${it.key}") {
            if (i > 0) Hairline(Modifier.padding(horizontal = Gutter))
            ItemRow(it, at, overdue = overdue, showWhen = showWhen)
        }
    }
}

@Composable
private fun ClaudeBlock(asks: List<Plan.Ask>, at: LocalDateTime) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val counts = asks.groupingBy { it.kind }.eachCount().entries.joinToString(" · ") { "${it.key.lowercase()} ${it.value}" }
    fun text() = Plan.forClaude(asks, at)
    Column {
        SectionLabel("Ответить Claude", counts)
        Text(
            "Соберу всё, о чём Claude ждёт ответа, в формате «вопрос — ответ». Впиши ответы и отправь " +
                "в чат с Claude: он обновит сроки и баллы, а сюда они придут при обновлении.",
            style = Type.small, color = p.ink2, modifier = Modifier.padding(horizontal = Gutter, vertical = 6.dp),
        )
        Row(Modifier.padding(horizontal = Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlatButton("Скопировать", filled = false) {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Бауманка → Claude", text()))
                Toast.makeText(ctx, "Скопировано — вставь в чат с Claude", Toast.LENGTH_SHORT).show()
            }
            FlatButton("Открыть в Claude", filled = true) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text())
                try {
                    ctx.startActivity(Intent(send).setPackage("com.anthropic.claude"))
                } catch (_: ActivityNotFoundException) {
                    ctx.startActivity(Intent.createChooser(send, "Отправить Claude"))
                }
            }
        }
    }
}

@Composable
fun FlatButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .then(if (filled) Modifier.background(p.ink) else Modifier.border(1.dp, p.ink))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, style = Type.smallStrong, color = if (filled) p.bg else p.ink)
    }
}

// ---------------------------------------------------------------- баллы

fun LazyListScope.gradesScreen(data: Data, onSettings: () -> Unit) {
    item("header") {
        ScreenHeader(kicker = "по brs.md", title = "Баллы", sub = "Нажми на предмет, чтобы увидеть работы")
    }
    if (!data.personal) {
        item("connect") {
            val p = LocalPalette.current
            Spacer(Modifier.height(16.dp))
            Note("Баллы хранятся в репозитории bmstu-study. Подключи его в настройках →", p.accent, onSettings)
        }
        return
    }
    val shown = data.grades.filter { it.works.isNotEmpty() || it.semester != null }
    shown.forEach { g -> item("g-${g.slug}") { GradeBlock(g) } }
    item("footer") {
        val p = LocalPalette.current
        Text(
            "Чёрная риска — порог. Процент считается только по оценённым работам.",
            style = Type.small, color = p.ink3, modifier = Modifier.padding(horizontal = Gutter, vertical = 20.dp),
        )
    }
}

private fun fmt(x: Double): String =
    if (x == Math.floor(x)) x.toLong().toString() else String.format(Locale("ru"), "%.1f", x)

@Composable
private fun GradeBlock(g: Grade) {
    val p = LocalPalette.current
    var open by rememberSaveable(g.slug) { mutableStateOf(false) }
    val color = when {
        g.lost -> p.hot
        g.low -> p.hot
        else -> p.ink
    }
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        SectionLabel(g.name, g.semester?.let { s -> "семестр $s" + (g.threshold?.let { ", порог $it" } ?: "") })
        Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (g.max > 0) {
                    Text(fmt(g.got), style = Type.title, color = color)
                    Text(" из ${fmt(g.max)}", style = Type.body, color = p.ink2, modifier = Modifier.padding(bottom = 2.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${Math.round(100 * g.got / g.max)} %", style = Type.heading, color = color)
                } else {
                    Text(
                        when {
                            g.passes > 0 -> "Зачтено работ: ${g.passes}"
                            else -> "Оценок пока нет"
                        },
                        style = Type.bodyStrong, color = p.ink,
                    )
                }
            }
            if (g.max > 0) {
                Spacer(Modifier.height(6.dp))
                val marker = if (g.semester != null && g.threshold != null) g.threshold.toFloat() / g.semester else null
                ProgressLine((g.got / g.max).toFloat(), color = if (g.lost || g.low) p.hot else p.ok, marker = marker)
            }
            val verdict = when {
                g.lost -> "Порог уже не набрать даже на максимум — поговори с преподавателем"
                g.low -> "Ниже порога по оценённым работам"
                g.semester != null && g.threshold != null -> {
                    val need = g.threshold - g.got
                    if (need > 0) "До порога ${fmt(need)} б., разыграно ${fmt(g.max)} из ${g.semester}" else "Порог набран"
                }
                else -> null
            }
            if (verdict != null) {
                Spacer(Modifier.height(4.dp))
                Text(verdict, style = Type.small, color = if (g.lost || g.low) p.hot else p.ink2)
            }
            if (g.passes > 0 && g.max > 0) Text("Зачтено работ: ${g.passes}", style = Type.small, color = p.ink2)
            if (open) {
                Spacer(Modifier.height(8.dp))
                g.works.forEach { w ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = Type.small, color = p.ink)
                            if (w.date.isNotEmpty()) Text(w.date, style = Type.small, color = p.ink3)
                        }
                        Text(
                            when {
                                w.gotNum != null && w.max != null -> "${fmt(w.gotNum)} / ${fmt(w.max)}"
                                w.gotNum != null -> fmt(w.gotNum)
                                w.got.isNotEmpty() && w.got != "—" -> w.got
                                w.max != null -> "— / ${fmt(w.max)}"
                                else -> "—"
                            },
                            style = Type.smallStrong,
                            color = if (w.gotNum == null && (w.got.isEmpty() || w.got == "—")) p.ink3 else p.ink,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 12.dp).width(84.dp),
                        )
                    }
                }
            }
        }
    }
}
