package com.phonechecker.checks

import android.app.Activity
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Status
import com.phonechecker.ui.C
import com.phonechecker.ui.Cap
import com.phonechecker.ui.Card
import com.phonechecker.ui.Header
import com.phonechecker.ui.KV
import com.phonechecker.ui.Page
import com.phonechecker.ui.Pip
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import com.phonechecker.ui.T
import com.phonechecker.ui.ToneButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Простой линейный график нескольких рядов в общих координатах. */
@Composable
fun LineChart(series: List<Pair<List<Float>, Color>>, minY: Float, maxY: Float, points: Int) {
    val grid = C.border
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        for (i in 0..3) {
            val y = size.height * i / 3
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        series.forEach { (vals, color) ->
            if (vals.size < 2) return@forEach
            val p = Path()
            vals.forEachIndexed { i, v ->
                val x = size.width * i / (points - 1).coerceAtLeast(1)
                val y = size.height * (1 - ((v - minY) / (maxY - minY)).coerceIn(0f, 1f))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, color, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Pip(color); T(text, 12.sp, C.text2)
}

// ───────────────────────────── Зарядка ─────────────────────────────

/** Ток в мА (+ заряд, − разряд). Прошивки отдают то мкА, то мА, и знак тоже разный. */
fun currentMa(bm: BatteryManager): Int? {
    val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    if (raw == Int.MIN_VALUE || raw == 0) return null
    return if (abs(raw) > 20_000) raw / 1000 else raw
}

@Composable
fun ChargeCheck(onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    val b = rememberBattery()
    val latest by rememberUpdatedState(b)
    val plugged = b.plugged != 0
    val hist = remember { mutableStateListOf<Float>() }
    var ma by remember { mutableStateOf<Int?>(null) }
    var maxMa by remember { mutableIntStateOf(0) }
    var maxW by remember { mutableStateOf(0f) }
    var sign by remember { mutableIntStateOf(1) }

    LaunchedEffect(plugged) {
        // Некоторые прошивки при зарядке отдают отрицательный ток — определяем знак по первому замеру.
        var signKnown = false
        while (isActive) {
            val c = currentMa(bm)
            if (c != null && plugged) {
                if (!signKnown) { sign = if (c < 0) -1 else 1; signKnown = true }
                val v = c * sign
                ma = v
                if (v > maxMa) maxMa = v
                val w = v / 1000f * (latest.voltageV ?: 3.9f)
                if (w > maxW) maxW = w
                hist += v.toFloat()
                if (hist.size > 60) hist.removeAt(0)
            } else ma = c
            delay(1000)
        }
    }

    val p = b.voltageV?.let { v -> ma?.let { it / 1000f * v } }
    Page(bottom = {
        PrimaryButton("Далее") {
            val st = when {
                maxMa == 0 -> Status.SKIPPED
                maxMa >= 500 -> Status.OK
                else -> Status.WARN
            }
            onResult(
                CheckResult(
                    st, if (st == Status.SKIPPED) "пропущено" else "до $maxMa мА",
                    details = listOf("Макс. ток" to "$maxMa мА", "Макс. мощность" to "${fmt(maxW)} Вт", "Способ" to b.pluggedText),
                ),
            )
        }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("Дополнительная проверка", "Зарядка", "Подключите кабель. Ток и мощность обновляются раз в секунду. Пошевелите штекер: провалы тока выдают плохой разъём.")
        Card {
            Cap(if (plugged) "Ток заряда" else "Кабель не подключён")
            T(if (plugged && ma != null) "$ma мА" else "—", 44.sp, if (plugged) C.warn.fg else C.text3, FontWeight.ExtraBold)
            if (hist.size >= 2) LineChart(listOf(hist.toList() to C.warn.fg), 0f, max(1000f, hist.max() * 1.2f), 60)
        }
        Card {
            KV("Мощность", p?.takeIf { plugged }?.let { "${fmt(it)} Вт" } ?: "—")
            KV("Напряжение", b.voltageV?.let { "${fmt(it, 2)} В" } ?: NO_DATA)
            KV("Максимум", if (maxMa > 0) "$maxMa мА · ${fmt(maxW)} Вт" else "—")
            KV("Заряд", b.level?.let { "$it%" } ?: NO_DATA)
            KV("Подключение", b.pluggedText)
        }
        if (currentMa(bm) == null) T("Телефон не отдаёт ток — $NO_DATA.", 13.sp, C.text3)
    }
}

// ───────────────────────────── Стресс-тест ─────────────────────────────

/** Максимальная температура CPU из thermal_zone (если доступна), °C. */
fun cpuTemp(): Float? {
    val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
    return zones.mapNotNull { z ->
        val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: return@mapNotNull null
        if (listOf("cpu", "tsens", "soc", "mtktscpu", "big", "little").none { it in type }) return@mapNotNull null
        val t = runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull() ?: return@mapNotNull null
        (if (t > 1000) t / 1000 else t).takeIf { it in 10f..130f }
    }.maxOrNull()
}

private const val STRESS_SEC = 120

@Composable
fun StressCheck(onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val view = LocalView.current
    val b = rememberBattery()
    val latest by rememberUpdatedState(b)
    var running by remember { mutableStateOf(false) }
    var sec by remember { mutableIntStateOf(0) }
    val bat = remember { mutableStateListOf<Float>() }
    val cpu = remember { mutableStateListOf<Float>() }
    var startBat by remember { mutableStateOf<Float?>(null) }
    var finished by remember { mutableStateOf(false) }

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        sec = 0; bat.clear(); cpu.clear(); startBat = latest.tempC
        coroutineScope {
            val cores = Runtime.getRuntime().availableProcessors()
            val workers = List(cores) {
                launch(Dispatchers.Default) {
                    var x = 1.0001
                    while (isActive) {
                        repeat(200_000) { x = sqrt(x * x + 1.0) - 0.5 }
                        if (x == 42.0) println() // не даём оптимизатору выкинуть цикл
                    }
                }
            }
            while (sec < STRESS_SEC && isActive) {
                delay(1000)
                sec++
                rememberSample(bat, latest.tempC)
                rememberSample(cpu, cpuTemp())
            }
            workers.forEach { it.cancel() }
        }
        running = false
        finished = true
    }

    val maxBat = bat.maxOrNull()
    val maxCpu = cpu.maxOrNull()
    val hot = (maxBat ?: 0f) >= 45f || (maxCpu ?: 0f) >= 90f

    Page(bottom = {
        if (running) ToneButton("Стоп", C.badAlt, Modifier.fillMaxWidth()) { running = false; finished = true }
        else if (!finished) PrimaryButton("Старт (2 минуты)") { running = true }
        else PrimaryButton("Далее") {
            val st = if (hot) Status.WARN else Status.OK
            onResult(
                CheckResult(
                    st, if (hot) "перегрев" else "норм",
                    details = listOf(
                        "Длительность" to "$sec с",
                        "Батарея" to "${startBat?.let { fmt(it) } ?: "?"} → ${maxBat?.let { fmt(it) } ?: "?"} °C",
                        "CPU макс." to (maxCpu?.let { "${fmt(it)} °C" } ?: NO_DATA),
                    ),
                ),
            )
        }
        if (!running) SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("Дополнительная проверка", "Стресс-тест", "2 минуты нагрузки на все ядра. Следим за нагревом батареи и процессора.")
        Card {
            Cap(if (running) "Идёт тест · ${STRESS_SEC - sec} с" else if (finished) "Готово · $sec с" else "Температура")
            LineChart(listOf(bat.toList() to C.warn.fg, cpu.toList() to C.bad.fg), 20f, 100f, STRESS_SEC)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(C.warn.fg, "батарея")
                Legend(C.bad.fg, if (cpu.isEmpty() && sec > 2) "CPU: $NO_DATA" else "CPU")
            }
        }
        Card {
            KV("Батарея сейчас", b.tempC?.let { "${fmt(it)} °C" } ?: NO_DATA)
            KV("Батарея макс.", maxBat?.let { "${fmt(it)} °C" } ?: "—", if ((maxBat ?: 0f) >= 45f) C.bad.fg else C.text)
            KV("CPU макс.", maxCpu?.let { "${fmt(it)} °C" } ?: "—", if ((maxCpu ?: 0f) >= 90f) C.bad.fg else C.text)
            KV("Ядер нагружено", Runtime.getRuntime().availableProcessors().toString())
        }
        if (hot) Card {
            T("Перегрев", 17.sp, C.bad.fg, FontWeight.Bold)
            T("Температура выше нормы. Это может быть признаком изношенной батареи или плохого охлаждения.", 14.sp, C.text2, lineHeight = 20.sp)
        }
    }
}

private fun rememberSample(list: SnapshotStateList<Float>, v: Float?) { if (v != null) list += v }

// ───────────────────────────── Подлинность ─────────────────────────────

data class AuthRow(val k: String, val v: String, val suspicious: String? = null)

private val FLAGSHIP = listOf(
    Regex("^SM-[SNFZ]\\d", RegexOption.IGNORE_CASE), Regex("pixel", RegexOption.IGNORE_CASE),
    Regex("iphone", RegexOption.IGNORE_CASE), Regex("galaxy\\s*[snz]", RegexOption.IGNORE_CASE),
    Regex("\\b(mate|p\\d0)\\b", RegexOption.IGNORE_CASE), Regex("(xiaomi|mi)\\s*1\\d", RegexOption.IGNORE_CASE),
    Regex("\\bpro\\b|\\bultra\\b|\\bmax\\b", RegexOption.IGNORE_CASE),
)

/** Минимальный SDK для «мажорной» версии Android. */
private val RELEASE_SDK = mapOf(8 to 26, 9 to 28, 10 to 29, 11 to 30, 12 to 31, 13 to 33, 14 to 34, 15 to 35, 16 to 36)

fun socName(): String {
    val cpuinfo = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") }?.substringAfter(":")?.trim() }.getOrNull()
    val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else ""
    return listOf(soc, cpuinfo.orEmpty(), Build.HARDWARE, prop("ro.board.platform"))
        .filter { it.isNotBlank() && it != "unknown" }.distinct().joinToString(" / ")
}

fun authRows(ctx: Context): List<AuthRow> {
    val model = Build.MODEL
    val flagship = FLAGSHIP.any { it.containsMatchIn(model) } ||
        (Build.MANUFACTURER.equals("apple", true))
    val rows = mutableListOf<AuthRow>()

    rows += AuthRow("Заявленная модель", "${Build.MANUFACTURER} $model", if (Build.MANUFACTURER.equals("apple", true)) "Android с маркой Apple — подделка" else null)

    // Подделки часто правят только ro.product.model, а вендорские свойства остаются от донора.
    val models = listOf("ro.product.vendor.model", "ro.product.odm.model", "ro.product.system.model", "ro.product.product.model")
        .map(::prop).filter { it.isNotBlank() }.distinct()
    val mismatch = models.filter { !it.equals(model, true) }
    rows += AuthRow("Модель в разделах", (listOf(model) + mismatch).joinToString(" · "), if (mismatch.isNotEmpty()) "модель отличается в системных разделах" else null)

    val soc = socName()
    val oldMtk = Regex("mt65(7|8)\\d", RegexOption.IGNORE_CASE).containsMatchIn(soc)
    rows += AuthRow("Процессор (SoC)", soc.ifEmpty { NO_DATA }, if (oldMtk && Build.VERSION.SDK_INT >= 26) "очень старый чип MediaTek под новой версией Android" else if (oldMtk && flagship) "бюджетный чип у «флагмана»" else null)

    val cores = Runtime.getRuntime().availableProcessors()
    val freqs = (0 until cores).mapNotNull { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrNull()
    }
    val freqText = freqs.distinct().sortedDescending().joinToString(", ") { "${it} МГц" }
    rows += AuthRow("Ядра", "$cores" + if (freqText.isNotEmpty()) " ($freqText)" else "", if (flagship && cores < 8) "у флагманов обычно 8 ядер" else null)

    val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    rows += AuthRow("Архитектура", Build.SUPPORTED_ABIS.joinToString(), if (!is64 && flagship) "только 32 бита — не похоже на флагман" else null)

    val major = Build.VERSION.RELEASE.substringBefore('.').toIntOrNull()
    val needSdk = major?.let { RELEASE_SDK[it] }
    rows += AuthRow(
        "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        if (needSdk != null && Build.VERSION.SDK_INT < needSdk) "версия подписана как $major, а API от более старой" else null,
    )

    val dm = DisplayMetrics()
    @Suppress("DEPRECATION")
    (ctx as Activity).windowManager.defaultDisplay.getRealMetrics(dm)
    val shortSide = minOf(dm.widthPixels, dm.heightPixels)
    rows += AuthRow(
        "Экран", "${dm.widthPixels}×${dm.heightPixels}, ${dm.densityDpi} dpi",
        if (flagship && shortSide < 1080) "низкое разрешение для флагмана" else null,
    )

    val ram = readStorage(ctx).ramTotal
    val ramGb = ramMarketingGb(ram)
    rows += AuthRow("ОЗУ", "$ramGb ГБ", if (flagship && ramGb < 6) "мало памяти для флагмана" else null)

    val total = StatFs(Environment.getDataDirectory().path).totalBytes
    rows += AuthRow("Встроенная память", "${declaredGb(total)} ГБ (${gbText(total)})", if (flagship && total < 60_000_000_000L) "мало памяти для флагмана" else null)

    return rows
}

@Composable
fun AuthCheck(onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { authRows(ctx) }
    val bad = rows.count { it.suspicious != null }
    Page(bottom = {
        PrimaryButton("Далее") {
            onResult(
                CheckResult(
                    if (bad > 0) Status.WARN else Status.OK,
                    if (bad > 0) "расхождений: $bad" else "совпадает",
                    details = rows.map { it.k to (it.v + (it.suspicious?.let { s -> " ⚠ $s" } ?: "")) },
                ),
            )
        }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("Дополнительная проверка", "Подлинность", "Сверяем заявленную модель с реальным железом. Подозрительное подсвечено жёлтым — сравните с характеристиками модели на сайте производителя.")
        Card {
            rows.forEach { r ->
                KV(r.k, r.v, if (r.suspicious != null) C.warn.fg else C.text)
                r.suspicious?.let { T("⚠ $it", 12.sp, C.warn.fg) }
            }
        }
        T(
            if (bad == 0) "Явных расхождений не найдено." else "Найдено расхождений: $bad. Это повод присмотреться, но не приговор.",
            13.sp, if (bad == 0) C.ok.fg else C.warn.fg,
        )
    }
}

