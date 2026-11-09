package com.phonechecker.checks

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Item
import com.phonechecker.data.Status
import com.phonechecker.ui.C
import com.phonechecker.ui.Header
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import com.phonechecker.ui.T
import com.phonechecker.ui.VerdictButtons
import com.phonechecker.ui.Card
import com.phonechecker.ui.ToneButton
import kotlin.math.abs
import kotlin.math.roundToInt

private val COLORS = listOf(Color.Black, Color.White, Color.Red, Color(0xFF00FF00), Color.Blue, Color(0xFF808080))
private const val QUICK_MS = 500L
private val PASSED = Color(0xFF00E600)

private enum class Phase { COLORS, TOUCH, VERDICT }

@Composable
fun ScreenTest(index: String, onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    var phase by remember { mutableStateOf(Phase.COLORS) }
    var touch by remember { mutableStateOf<Status?>(null) }

    var askExit by remember { mutableStateOf(false) }
    // Системное «назад» во время заливок и тача не выкидывает молча: пройденное потеряется.
    BackHandler(enabled = phase != Phase.VERDICT) { askExit = true }

    Box(Modifier.fillMaxSize()) {
    when (phase) {
        Phase.COLORS -> Immersive { ColorPhase { phase = Phase.TOUCH } }
        Phase.TOUCH -> Immersive {
            TouchPhase { status ->
                touch = status
                phase = Phase.VERDICT
            }
        }
        Phase.VERDICT -> Page(bottom = {
            VerdictButtons(
                onOk = { onResult(result(touch, Status.OK)) },
                onDefect = { onResult(result(touch, Status.DEFECT)) },
            )
            SecondaryButton("Пропустить") { onResult(result(touch, Status.SKIPPED)) }
        }) {
            Header(index, "Экран", "Были ли заметны битые пиксели, засветы, пятна или выгорание (остаточное изображение)?")
            Card {
                T("Тач-скрин", 15.sp, C.text2)
                val t = touch ?: Status.SKIPPED
                T(
                    when (t) { Status.OK -> "все клетки пройдены"; Status.DEFECT -> "есть непройденные зоны"; else -> "пропущено" },
                    17.sp, if (t == Status.SKIPPED) C.text2 else C.tone(t).fg, FontWeight.SemiBold,
                )
            }
            SecondaryButton("Пройти тест заново") { phase = Phase.COLORS }
        }
    }
    if (askExit) ExitDialog(
        onExit = { askExit = false; onSkip() },
        onDefect = { askExit = false; touch = Status.DEFECT; phase = Phase.VERDICT },
        onStay = { askExit = false },
    )
    }
}

@Composable
private fun ExitDialog(onExit: () -> Unit, onDefect: () -> Unit, onStay: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card)
                .border(1.dp, C.border, RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("Прервать тест экрана?", 20.sp, weight = FontWeight.Bold)
            T("Пройденное не сохранится. Если нашли дефект — отметьте его.", 14.sp, C.text2, lineHeight = 20.sp)
            PrimaryButton("Продолжить тест", onClick = onStay)
            ToneButton("Отметить дефект", C.badAlt, Modifier.fillMaxWidth(), onDefect)
            SecondaryButton("Выйти (пропустить)", onClick = onExit)
        }
    }
}

private fun result(touch: Status?, colors: Status): CheckResult {
    val t = touch ?: Status.SKIPPED
    val items = listOf(Item("Пиксели и засветы", colors), Item("Тач-скрин", t))
    val st = when {
        Status.DEFECT in listOf(t, colors) -> Status.DEFECT
        t == Status.SKIPPED && colors == Status.SKIPPED -> Status.SKIPPED
        Status.SKIPPED in listOf(t, colors) -> Status.WARN
        else -> Status.OK
    }
    val badge = when (st) {
        Status.WARN -> "проверено частично"
        else -> st.label
    }
    return CheckResult(st, badge, items = items)
}

/** Прячет системные панели и не даёт экрану гаснуть, пока показан контент. */
@Composable
internal fun Immersive(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val ctl = WindowCompat.getInsetsController(window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
    content()
}

/** Считает быстрые тапы (интервал < 500 мс) и зовёт onCount с их числом. */
private class TapCounter {
    private val taps = ArrayList<Long>()
    fun tap(now: Long): Int {
        taps.removeAll { now - it >= QUICK_MS }
        // Интервал считается между соседними тапами.
        if (taps.isNotEmpty() && now - taps.last() >= QUICK_MS) taps.clear()
        taps.add(now)
        return taps.size
    }
    fun reset() = taps.clear()
}

@Composable
private fun ColorPhase(done: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val counter = remember { TapCounter() }
    Box(
        Modifier.fillMaxSize().background(COLORS[step]).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (counter.tap(down.uptimeMillis) >= 2) {
                    counter.reset()
                    if (step + 1 < COLORS.size) step++ else done()
                }
            }
        },
    )
}

/** Клетки как в сервисном меню: периметр + две диагонали. */
private class Grid(val cols: Int, val rows: Int, val cw: Float, val ch: Float) {
    val active = BooleanArray(cols * rows)
    val passed = BooleanArray(cols * rows)
    val total: Int

    init {
        for (r in 0 until rows) for (c in 0 until cols) {
            if (r == 0 || c == 0 || r == rows - 1 || c == cols - 1) active[r * cols + c] = true
        }
        // Диагонали: по клетке на каждую строку, плюс соседняя по ходу, чтобы линия была сплошной.
        for (r in 0 until rows) {
            val c = (r.toFloat() * (cols - 1) / (rows - 1)).roundToInt()
            active[r * cols + c] = true
            active[r * cols + (cols - 1 - c)] = true
        }
        total = active.count { it }
    }

    /** Отмечает клетку под точкой; true — если что-то изменилось. */
    fun hit(p: Offset): Boolean {
        val c = (p.x / cw).toInt().coerceIn(0, cols - 1)
        val r = (p.y / ch).toInt().coerceIn(0, rows - 1)
        val i = r * cols + c
        if (!active[i] || passed[i]) return false
        passed[i] = true
        return true
    }
}

@Composable
private fun TouchPhase(done: (Status?) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val cellTarget = with(density) { 20.dp.toPx() }
        val grid = remember(w, h) {
            val cols = (w / cellTarget).toInt().coerceAtLeast(3)
            val rows = (h / cellTarget).toInt().coerceAtLeast(3)
            Grid(cols, rows, w / cols, h / rows)
        }
        val path = remember(w, h) { Path() }
        var tick by remember { mutableIntStateOf(0) }
        var passedCount by remember(w, h) { mutableIntStateOf(0) }
        var ask by remember { mutableStateOf(false) }
        val counter = remember { TapCounter() }
        val slop = with(density) { 16.dp.toPx() }

        Canvas(
            Modifier.fillMaxSize().pointerInput(grid) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = 0f
                    var last = down.position
                    path.moveTo(last.x, last.y)
                    if (grid.hit(last)) passedCount++
                    tick++
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val p = ch.position
                        moved += abs(p.x - last.x) + abs(p.y - last.y)
                        // Промежуточные точки, чтобы быстрый свайп не перепрыгивал клетки.
                        val steps = (maxOf(abs(p.x - last.x), abs(p.y - last.y)) / (grid.cw / 2)).toInt().coerceAtLeast(1)
                        for (s in 1..steps) {
                            val q = last + (p - last) * (s.toFloat() / steps)
                            if (grid.hit(q)) passedCount++
                        }
                        path.lineTo(p.x, p.y)
                        last = p
                        tick++
                        ch.consume()
                    }
                    if (moved < slop) {
                        if (counter.tap(down.uptimeMillis) >= 3) {
                            counter.reset()
                            if (passedCount >= grid.total) done(Status.OK) else ask = true
                        }
                    } else counter.reset()
                }
            },
        ) {
            tick // перерисовка при изменениях
            val border = Color(0xFF9AA0A6)
            for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
                val i = r * grid.cols + c
                if (!grid.active[i]) continue
                val tl = Offset(c * grid.cw, r * grid.ch)
                val sz = Size(grid.cw, grid.ch)
                drawRect(if (grid.passed[i]) PASSED else Color.White, tl, sz)
                drawRect(border, tl, sz, style = Stroke(1f))
            }
            drawPath(path, Color.Black, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Все клетки зелёные — тест пройден, тройной тап не нужен.
        val complete = passedCount >= grid.total
        LaunchedEffect(complete) {
            if (complete) {
                delay(400)
                done(Status.OK)
            }
        }

        if (ask) IncompleteDialog(
            left = grid.total - passedCount,
            onDefect = { done(Status.DEFECT) },
            onSkip = { done(Status.SKIPPED) },
            onContinue = { ask = false },
        )
    }
}

@Composable
private fun IncompleteDialog(left: Int, onDefect: () -> Unit, onSkip: () -> Unit, onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card)
                .border(1.dp, C.border, RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("Не все клетки пройдены", 20.sp, weight = FontWeight.Bold)
            T("Осталось клеток: $left. Если палец проходит, а клетка не зеленеет — это дефект тача.", 14.sp, C.text2, lineHeight = 20.sp)
            ToneButton("Дефект", C.badAlt, Modifier.fillMaxWidth(), onDefect)
            PrimaryButton("Продолжить тест", onClick = onContinue)
            SecondaryButton("Пропустить", onClick = onSkip)
        }
    }
}
