package com.bauman.pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bauman.pocket.Ru
import com.bauman.pocket.Semester
import java.time.Instant

class SettingsState(
    val repo: String,
    val token: String,
    val syncedAt: Long,
    val error: String?,
    val notifyNow: Boolean,
    val notifyDeadlines: Boolean,
)

@Composable
fun SettingsScreen(
    state: SettingsState,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (repo: String, token: String) -> Unit,
    onNotifyNow: (Boolean) -> Unit,
    onNotifyDeadlines: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    var repo by remember(state.repo) { mutableStateOf(state.repo) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.ink, unfocusedBorderColor = p.line, focusedLabelColor = p.ink, unfocusedLabelColor = p.ink2,
        cursorColor = p.accent, focusedTextColor = p.ink, unfocusedTextColor = p.ink,
    )

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = p.ink) }
            }
            Text("Настройки", style = Type.display, color = p.ink, modifier = Modifier.padding(horizontal = Gutter))
        }

        item {
            var editing by remember { mutableStateOf(state.token.isBlank()) }
            var howTo by remember { mutableStateOf(false) }
            SectionLabel("Данные")
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                when {
                    state.error != null -> Text(state.error, style = Type.small, color = p.hot)
                    state.syncedAt > 0 -> {
                        val t = Instant.ofEpochMilli(state.syncedAt).atZone(Semester.ZONE).toLocalDateTime()
                        Text(
                            "${state.repo} · загружено ${Ru.ddmm(t.toLocalDate())} в ${Ru.hm(t.toLocalTime())}",
                            style = Type.small, color = p.ink2,
                        )
                    }
                    else -> Text("Сроки и баллы берутся из bmstu-study — нужен токен GitHub", style = Type.small, color = p.ink2)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.token.isNotBlank()) {
                        FlatButton(if (busy) "Загружаю…" else "Обновить", filled = true) { if (!busy) onSave(state.repo, state.token) }
                    }
                    FlatButton(if (state.token.isBlank()) "Подключить" else "Токен", filled = false) { editing = !editing }
                    FlatButton("Инструкция", filled = false) { howTo = !howTo }
                }
                if (editing) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = repo, onValueChange = { repo = it.trim() }, singleLine = true,
                        label = { Text("Репозиторий", style = Type.small) }, textStyle = Type.body,
                        shape = RectangleShape, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = token, onValueChange = { token = it.trim() }, singleLine = true,
                        label = { Text("Токен GitHub", style = Type.small) }, textStyle = Type.body,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RectangleShape, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    FlatButton("Сохранить и загрузить", filled = true) {
                        if (!busy && token.isNotBlank()) {
                            onSave(repo, token)
                            editing = false
                        }
                    }
                }
                if (howTo) {
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "github.com → аватар → Settings → Developer settings.",
                        "Personal access tokens → Fine-grained tokens → Generate new token.",
                        "Repository access: Only select repositories → bmstu-study.",
                        "Permissions → Repository → Contents: Read-only.",
                        "Срок — до конца семестра. Вставь токен через «Токен».",
                    ).forEachIndexed { i, line ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("${i + 1}", style = Type.smallStrong, color = p.accent, modifier = Modifier.padding(end = 12.dp))
                            Text(line, style = Type.small, color = p.ink)
                        }
                    }
                }
            }
        }

        item {
            SectionLabel("Уведомления")
            Toggle(
                "Пара на экране блокировки",
                "За 15 минут до пары и пока она идёт: что, где и сколько осталось",
                state.notifyNow, onNotifyNow,
            )
            Hairline(Modifier.padding(horizontal = Gutter))
            Toggle(
                "Напоминания о сроках",
                "Накануне в 20:00 и в день сдачи",
                state.notifyDeadlines, onNotifyDeadlines,
            )
        }

        item {
            var huawei by remember { mutableStateOf(false) }
            Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                FlatButton("Уведомления опаздывают?", filled = false) { huawei = !huawei }
            }
            if (huawei) {
                Text(
                    "Huawei и Honor усыпляют приложения в фоне. Настройки → Батарея → Запуск приложений → " +
                        "Бауманка → выключи «Автоматически» и оставь включёнными все три пункта.",
                    style = Type.small, color = p.ink2, modifier = Modifier.padding(horizontal = Gutter, vertical = 6.dp),
                )
            }
            Text(
                "Бауманка · ИУ3-13Б · шрифт Onest (OFL)",
                style = Type.small, color = p.ink3, modifier = Modifier.padding(horizontal = Gutter, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = Type.bodyStrong, color = p.ink)
            Text(sub, style = Type.small, color = p.ink2)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.accent, checkedThumbColor = p.raised,
                uncheckedTrackColor = p.line, uncheckedThumbColor = p.ink3, uncheckedBorderColor = p.line,
            ),
        )
    }
}
