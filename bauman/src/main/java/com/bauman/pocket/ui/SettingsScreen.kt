package com.bauman.pocket.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
            SectionLabel("Откуда брать данные")
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                Text(
                    "Расписание встроено в приложение. Сроки, контрольные и баллы приходят из твоего репозитория " +
                        "bmstu-study: Claude обновляет его, приложение подтягивает изменения раз в 6 часов и при открытии.",
                    style = Type.small, color = p.ink2,
                )
                Spacer(Modifier.height(16.dp))
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
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onSave(repo, token) }, enabled = !busy && token.isNotBlank(),
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg, disabledContainerColor = p.line, disabledContentColor = p.ink3),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(if (busy) "Загружаю…" else "Сохранить и загрузить", style = Type.bodyStrong)
                }
                Spacer(Modifier.height(10.dp))
                when {
                    state.error != null -> Text(state.error, style = Type.small, color = p.hot)
                    state.syncedAt > 0 -> {
                        val t = Instant.ofEpochMilli(state.syncedAt).atZone(Semester.ZONE).toLocalDateTime()
                        Text("Загружено ${Ru.ddmm(t.toLocalDate())} в ${Ru.hm(t.toLocalTime())}", style = Type.small, color = p.ok)
                    }
                }
            }
        }

        item {
            SectionLabel("Как получить токен")
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                listOf(
                    "github.com → аватар → Settings → Developer settings.",
                    "Personal access tokens → Fine-grained tokens → Generate new token.",
                    "Repository access: Only select repositories → bmstu-study.",
                    "Permissions → Repository → Contents: Read-only. Больше ничего не нужно.",
                    "Срок — до конца семестра. Скопируй токен и вставь выше.",
                ).forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}", style = Type.smallStrong, color = p.accent, modifier = Modifier.padding(end = 12.dp))
                        Text(s, style = Type.small, color = p.ink)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Токен только читает один репозиторий и хранится в памяти приложения на телефоне.",
                    style = Type.small, color = p.ink3,
                )
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
            SectionLabel("Если уведомления опаздывают")
            Text(
                "Huawei и Honor усыпляют приложения в фоне. Настройки → Батарея → Запуск приложений → " +
                    "Бауманка → выключи «Автоматически» и оставь включёнными все три пункта.",
                style = Type.small, color = p.ink2, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
        }

        item {
            SectionLabel("О приложении")
            Text(
                "Бауманка · ИУ3-13Б · семестр с 31 августа 2026. Шрифт Onest (SIL Open Font License).",
                style = Type.small, color = p.ink3, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
            Spacer(Modifier.height(32.dp))
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
