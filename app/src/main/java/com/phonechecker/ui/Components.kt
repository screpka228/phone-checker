package com.phonechecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val C: AppColors @Composable get() = LocalColors.current

@Composable
fun T(
    text: String,
    size: TextUnit = 15.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = Text(text, modifier, color = color, fontSize = size, fontWeight = weight, textAlign = align, lineHeight = lineHeight)

/** Мелкая подпись капсом («ЗДОРОВЬЕ», группы отчёта). */
@Composable
fun Cap(text: String, color: Color = C.text3, size: TextUnit = 12.sp) =
    Text(text.uppercase(), color = color, fontSize = size, letterSpacing = 0.08.em)

/** Экран с отступами 28/20 как в макетах; контент прокручивается, кнопки прижаты к низу. */
@Composable
fun Page(
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(C.bg).systemBarsPadding().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        bottom()
    }
}

@Composable
fun Header(over: String, title: String, desc: String? = null) {
    Column(Modifier.padding(top = 12.dp)) {
        T(over, 13.sp, C.text2)
        T(title, 26.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        if (desc != null) T(desc, 14.sp, C.text2, modifier = Modifier.padding(top = 6.dp), lineHeight = 20.sp)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, gap: Int = 10, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card)
            .border(1.dp, C.border, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}

@Composable
fun KV(k: String, v: String, vColor: Color = C.text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        // Ключ не сжимается, длинное значение переносится под себя.
        Text(k, Modifier.padding(end = 12.dp), color = C.text2, fontSize = 15.sp, softWrap = false)
        T(v, 15.sp, vColor, modifier = Modifier.weight(1f), align = TextAlign.End)
    }
}

@Composable
fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(C.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, height: Int = 56, size: TextUnit = 17.sp, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = height.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) C.accent else C.track)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, size, if (enabled) C.onAccent else C.text3, FontWeight.Bold) }
}

@Composable
fun SecondaryButton(text: String, color: Color = C.text2, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, C.outline, RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, 15.sp, color) }
}

/** Пара кнопок «Норм / Дефект» с цветами статусов. */
@Composable
fun VerdictButtons(onOk: () -> Unit, onDefect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToneButton("Норм", C.ok, Modifier.weight(1f), onOk)
        ToneButton("Дефект", C.badAlt, Modifier.weight(1f), onDefect)
    }
}

@Composable
fun ToneButton(text: String, tone: Tone, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(tone.bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, 17.sp, tone.fg, FontWeight.Bold) }
}

@Composable
fun Badge(text: String, tone: Tone) {
    Box(Modifier.clip(CircleShape).background(tone.bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        T(text, 12.sp, tone.fg, FontWeight.SemiBold)
    }
}

@Composable
fun Pip(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun ThemeToggle(dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(if (dark) "☀" else "☾", 16.sp) }
}

@Composable
fun Switch(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(50.dp, 30.dp).clip(CircleShape).background(if (on) C.accent else C.switchOff)
            .clickable(onClick = onToggle).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun Disclaimer() = T(
    "Результат информационный и не гарантирует отсутствие скрытых дефектов.",
    12.sp, C.text3, lineHeight = 16.sp,
)

/** Кликабельность без ripple — для заголовка-«пасхалки». */
@Composable
fun Modifier.plainClick(onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
