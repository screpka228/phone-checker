package com.phonechecker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.phonechecker.data.Status

/** Пара «текст + фон» для статусов и плашек иконок. */
data class Tone(val fg: Color, val bg: Color)

data class AppColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val border: Color,
    val outline: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Tone,
    val warn: Tone,
    val bad: Tone,
    val badAlt: Tone,
    val info: Tone,
    val idle: Tone,
    val purple: Tone,
    val cyan: Tone,
    val neutral: Tone,
    val track: Color,
    val switchOff: Color,
    val scrim: Color,
) {
    fun tone(s: Status): Tone = when (s) {
        Status.OK -> ok
        Status.WARN -> warn
        Status.DEFECT -> badAlt
        Status.INFO -> info
        Status.NONE, Status.SKIPPED -> idle
    }

    fun pip(s: Status): Color = when (s) {
        Status.NONE, Status.SKIPPED -> idleDot
        else -> tone(s).fg
    }

    val idleDot: Color get() = if (dark) Color(0xFF3C4043) else Color(0xFFBDC1C6)
}

val DarkColors = AppColors(
    dark = true,
    bg = Color(0xFF0B0D11),
    card = Color(0xFF14171D),
    border = Color(0xFF22262E),
    outline = Color(0xFF2A2E36),
    text = Color(0xFFE8EAED),
    text2 = Color(0xFF9AA0A6),
    text3 = Color(0xFF7B818A),
    accent = Color(0xFF8AB4F8),
    onAccent = Color(0xFF0B0D11),
    ok = Tone(Color(0xFF81C995), Color(0xFF1E3A2A)),
    warn = Tone(Color(0xFFFDD663), Color(0xFF3A3018)),
    bad = Tone(Color(0xFFF28B82), Color(0xFF3A2020)),
    badAlt = Tone(Color(0xFFF28B82), Color(0xFF3A1D2A)),
    info = Tone(Color(0xFF8AB4F8), Color(0xFF1D2A3D)),
    idle = Tone(Color(0xFF9AA0A6), Color(0xFF22262E)),
    purple = Tone(Color(0xFFC58AF9), Color(0xFF2A1D3D)),
    cyan = Tone(Color(0xFF78D9EC), Color(0xFF1D3A3A)),
    neutral = Tone(Color(0xFFE8EAED), Color(0xFF22262E)),
    track = Color(0xFF22262E),
    switchOff = Color(0xFF3C4043),
    scrim = Color(0x99000000),
)

val LightColors = AppColors(
    dark = false,
    bg = Color(0xFFF5F6F8),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFE1E4E8),
    outline = Color(0xFFD0D4DA),
    text = Color(0xFF1F2328),
    text2 = Color(0xFF5F6368),
    text3 = Color(0xFF80868B),
    accent = Color(0xFF1A73E8),
    onAccent = Color(0xFFFFFFFF),
    ok = Tone(Color(0xFF188038), Color(0xFFE6F4EA)),
    warn = Tone(Color(0xFFB06000), Color(0xFFFEF7E0)),
    bad = Tone(Color(0xFFD93025), Color(0xFFFCE8E6)),
    badAlt = Tone(Color(0xFFD93025), Color(0xFFFCE8EC)),
    info = Tone(Color(0xFF1A73E8), Color(0xFFE8F0FE)),
    idle = Tone(Color(0xFF5F6368), Color(0xFFF1F3F4)),
    purple = Tone(Color(0xFF8430CE), Color(0xFFF3E8FD)),
    cyan = Tone(Color(0xFF007B83), Color(0xFFE4F7FB)),
    neutral = Tone(Color(0xFF1F2328), Color(0xFFF1F3F4)),
    track = Color(0xFFE8EAED),
    switchOff = Color(0xFFBDC1C6),
    scrim = Color(0x66000000),
)

val LocalColors = staticCompositionLocalOf { DarkColors }

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides if (dark) DarkColors else LightColors, content = content)
}
