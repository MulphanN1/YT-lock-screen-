package com.bauman.pocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bauman.pocket.R

/** Бумага и чернила: тёплый фон, почти чёрный текст, один синий акцент и красный для горящего. */
@Immutable
data class Palette(
    val bg: Color,
    val raised: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val accent: Color,
    val accentWash: Color,
    val hot: Color,
    val ok: Color,
)

val Light = Palette(
    bg = Color(0xFFF4F1EA),
    raised = Color(0xFFFBF9F4),
    ink = Color(0xFF1B1A18),
    ink2 = Color(0xFF615D55),
    ink3 = Color(0xFFA19C91),
    line = Color(0xFFDCD6C9),
    accent = Color(0xFF2343A8),
    accentWash = Color(0xFFE3E6F1),
    hot = Color(0xFFC0391B),
    ok = Color(0xFF3D7A4C),
)

val Dark = Palette(
    bg = Color(0xFF151412),
    raised = Color(0xFF1D1C19),
    ink = Color(0xFFEDE9E0),
    ink2 = Color(0xFFA9A397),
    ink3 = Color(0xFF6C675E),
    line = Color(0xFF2F2D29),
    accent = Color(0xFF93A8F5),
    accentWash = Color(0xFF232838),
    hot = Color(0xFFF08A68),
    ok = Color(0xFF86C095),
)

val LocalPalette = staticCompositionLocalOf { Light }

@OptIn(ExperimentalTextApi::class)
private fun onest(weight: Int) = Font(
    R.font.onest,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Onest = FontFamily(onest(400), onest(500), onest(600), onest(700))

object Type {
    private val base = TextStyle(fontFamily = Onest, fontFeatureSettings = "tnum")

    val display = base.copy(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight(600), letterSpacing = (-0.6).sp)
    val title = base.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight(600), letterSpacing = (-0.3).sp)
    val heading = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight(600))
    val body = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight(400))
    val bodyStrong = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight(500))
    val small = base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight(400))
    val smallStrong = base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight(500))
    val label = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(600), letterSpacing = 0.9.sp)
}

@Composable
fun BaumanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = p.accent, onPrimary = p.bg, background = p.bg, surface = p.bg, onSurface = p.ink,
            onBackground = p.ink, surfaceVariant = p.raised, onSurfaceVariant = p.ink2, outline = p.line, error = p.hot)
    } else {
        lightColorScheme(primary = p.accent, onPrimary = p.raised, background = p.bg, surface = p.bg, onSurface = p.ink,
            onBackground = p.ink, surfaceVariant = p.raised, onSurfaceVariant = p.ink2, outline = p.line, error = p.hot)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
