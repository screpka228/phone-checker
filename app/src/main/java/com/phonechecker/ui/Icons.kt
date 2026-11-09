package com.phonechecker.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.phonechecker.data.CheckId

/** Кусок иконки: SVG-путь, обводка или заливка. */
class Part(val d: String, val fill: Boolean = false)

private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h${-(w - 2 * r)}a$r $r 0 0 1 -$r -${r}v${-(h - 2 * r)}a$r $r 0 0 1 $r -${r}z"

private fun rect(x: Float, y: Float, w: Float, h: Float) = "M$x ${y}h${w}v${h}h${-w}z"
private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = "M$x1 ${y1}L$x2 $y2"

/** Иконки из макетов (viewBox 24×24, stroke 1.8, скруглённые концы). */
object Ic {
    private val SCREEN = listOf(Part(rr(6f, 2.5f, 12f, 19f, 2.5f)), Part(line(11f, 18.5f, 13f, 18.5f)))
    private val BATTERY = listOf(Part(rr(2f, 7f, 17f, 10f, 2f)), Part(line(22f, 10.5f, 22f, 13.5f)), Part(rect(5f, 10f, 8f, 4f), fill = true))
    private val MEMORY = listOf(
        Part(rr(5f, 5f, 14f, 14f, 2f)), Part(rect(9.5f, 9.5f, 5f, 5f)),
        Part(line(9f, 2.5f, 9f, 5f) + line(15f, 2.5f, 15f, 5f) + line(9f, 19f, 9f, 21.5f) + line(15f, 19f, 15f, 21.5f)),
    )
    private val SENSORS = listOf(
        Part(circle(12f, 12f, 2f), fill = true),
        Part("M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.5 5.5a9 9 0 0 0 0 13M18.5 5.5a9 9 0 0 1 0 13"),
    )
    private val CONNECT = listOf(
        Part("M2.5 9.5a14 14 0 0 1 19 0M5.5 13a9.5 9.5 0 0 1 13 0M8.5 16.3a5 5 0 0 1 7 0"),
        Part(circle(12f, 19.5f, 1f), fill = true),
    )
    private val CAMERA = listOf(Part(rr(2.5f, 7f, 19f, 13f, 2.5f)), Part(circle(12f, 13.5f, 3.5f)))
    private val CHARGE = listOf(Part("M13 2L4 14h7l-1 8 9-12h-7z"))
    private val STRESS = listOf(Part("M12 3v10"), Part(circle(12f, 17f, 4f)))
    private val AUTH = listOf(Part("M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"), Part("M8.5 12l2.5 2.5 4.5-5"))
    private val SYSTEM = listOf(
        Part(line(4f, 6f, 20f, 6f) + line(4f, 12f, 20f, 12f) + line(4f, 18f, 20f, 18f)),
        Part(circle(9f, 6f, 2f) + circle(15f, 12f, 2f) + circle(8f, 18f, 2f), fill = true),
    )

    fun of(id: CheckId): List<Part> = when (id) {
        CheckId.SCREEN -> SCREEN
        CheckId.BATTERY -> BATTERY
        CheckId.MEMORY -> MEMORY
        CheckId.SENSORS -> SENSORS
        CheckId.CONNECT -> CONNECT
        CheckId.SYSTEM -> SYSTEM
        CheckId.CAMERA -> CAMERA
        CheckId.CHARGE -> CHARGE
        CheckId.STRESS -> STRESS
        CheckId.AUTH -> AUTH
    }
}

@Composable
fun CheckIcon(id: CheckId, tint: Color, plate: Color, size: Dp = 20.dp) {
    // У «Системы» кружки-ползунки залиты цветом плашки, у остальных заливка = цвет линии.
    val hole = if (id == CheckId.SYSTEM) plate else null
    val v = remember(id, tint, hole) { build(Ic.of(id), tint, hole) }
    Image(v, null, Modifier.size(size))
}

private fun build(parts: List<Part>, tint: Color, hole: Color?): ImageVector {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (p in parts) {
        b.addPath(
            pathData = addPathNodes(p.d),
            fill = if (p.fill) SolidColor(hole ?: tint) else null,
            stroke = if (p.fill && hole == null) null else SolidColor(tint),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}
