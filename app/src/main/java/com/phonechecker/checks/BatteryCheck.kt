package com.phonechecker.checks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Status
import com.phonechecker.ui.Bar
import com.phonechecker.ui.C
import com.phonechecker.ui.Cap
import com.phonechecker.ui.Card
import com.phonechecker.ui.Header
import com.phonechecker.ui.KV
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import com.phonechecker.ui.T
import java.io.File
import kotlin.math.roundToInt

const val NO_DATA = "нет данных"

data class BatteryInfo(
    val level: Int?,
    val tempC: Float?,
    val voltageV: Float?,
    val health: Int,
    val plugged: Int,
    val cycles: Int?,
    /** Остаточная ёмкость в % от заводской, если удалось оценить. */
    val healthPct: Int?,
    val healthSource: String,
    val designMah: Int?,
) {
    val healthText: String
        get() = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Хорошее"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Перегрев"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Неисправна"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Перенапряжение"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Сбой"
            BatteryManager.BATTERY_HEALTH_COLD -> "Переохлаждение"
            else -> NO_DATA
        }

    val healthBad get() = health in listOf(
        BatteryManager.BATTERY_HEALTH_DEAD, BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE,
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE, BatteryManager.BATTERY_HEALTH_OVERHEAT,
    )

    val pluggedText: String
        get() = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "от сети ✓"
            BatteryManager.BATTERY_PLUGGED_USB -> "по USB ✓"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "беспроводная ✓"
            0 -> "не подключена"
            else -> "по кабелю ✓"
        }
}

fun readBattery(ctx: Context, i: Intent?): BatteryInfo {
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val level = if (lvl >= 0 && scale > 0) lvl * 100 / scale else null
    val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
    val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
    val cycles = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null

    val design = designCapacityMah(ctx)
    var pct: Int? = null
    var src = ""
    // 1) sysfs: charge_full / charge_full_design (читается не на всех прошивках).
    val full = sysInt("charge_full")
    val fullDesign = sysInt("charge_full_design")
    if (full != null && fullDesign != null && fullDesign > 0 && full > 0) {
        pct = (full * 100f / fullDesign).roundToInt()
        src = "по данным контроллера"
    }
    // 2) Оценка: счётчик заряда / текущий % против заводской ёмкости.
    if (pct == null && design != null && level != null && level >= 15) {
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (counter > 0 && counter != Int.MIN_VALUE) {
            val fullMah = counter / 1000f / (level / 100f)
            val p = (fullMah * 100 / design).roundToInt()
            if (p in 30..130) {
                pct = p.coerceAtMost(100)
                src = "оценка по счётчику заряда"
            }
        }
    }
    return BatteryInfo(
        level, temp?.div(10f), volt?.div(1000f),
        i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0,
        i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
        cycles, pct, src, design,
    )
}

private fun sysInt(name: String): Int? = runCatching {
    File("/sys/class/power_supply/battery/$name").readText().trim().toInt()
}.getOrNull()

/** Заводская ёмкость из скрытого PowerProfile; на многих прошивках доступна. */
fun designCapacityMah(ctx: Context): Int? = runCatching {
    val cls = Class.forName("com.android.internal.os.PowerProfile")
    val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
    (cls.getMethod("getBatteryCapacity").invoke(pp) as Double).roundToInt()
}.getOrNull()?.takeIf { it > 500 }

@Composable
fun rememberBattery(): BatteryInfo {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(readBattery(ctx, ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { info = readBattery(c, i) }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { ctx.unregisterReceiver(r) }
    }
    return info
}

fun fmt(v: Float, digits: Int = 1): String = String.format(java.util.Locale("ru"), "%.${digits}f", v)

@Composable
fun BatteryCheck(index: String, onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val b = rememberBattery()
    val hp = b.healthPct
    val status = when {
        b.healthBad -> Status.DEFECT
        hp != null && hp < 80 -> Status.WARN
        else -> Status.OK
    }
    val badge = when {
        b.healthBad -> b.healthText.lowercase()
        hp != null && hp < 100 -> "износ ${100 - hp}%"
        else -> "норм"
    }
    val tone = if (hp == null) C.idle else if (hp >= 85) C.ok else if (hp >= 70) C.warn else C.bad

    Page(bottom = {
        PrimaryButton("Далее") {
            onResult(
                CheckResult(
                    status, badge,
                    details = listOf(
                        "Здоровье" to (hp?.let { "$it% (${b.healthSource})" } ?: NO_DATA),
                        "Заводская ёмкость" to (b.designMah?.let { "$it мА·ч" } ?: NO_DATA),
                        "Заряд" to (b.level?.let { "$it%" } ?: NO_DATA),
                        "Температура" to (b.tempC?.let { "${fmt(it)} °C" } ?: NO_DATA),
                        "Напряжение" to (b.voltageV?.let { "${fmt(it, 2)} В" } ?: NO_DATA),
                        "Состояние" to b.healthText,
                        "Циклы" to (b.cycles?.toString() ?: NO_DATA),
                    ),
                ),
            )
        }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("$index · автоматически", "Батарея", "Данные из системы. Если телефон их не отдаёт, пишем «нет данных».")
        Card {
            Cap("Здоровье")
            T(hp?.let { "$it%" } ?: NO_DATA, if (hp != null) 44.sp else 24.sp, tone.fg, FontWeight.ExtraBold)
            Bar((hp ?: 0) / 100f, tone.fg)
            if (hp != null) T(b.healthSource, 12.sp, C.text3)
        }
        Card {
            KV("Заряд", b.level?.let { "$it%" } ?: NO_DATA)
            KV("Температура", b.tempC?.let { "${fmt(it)} °C" } ?: NO_DATA)
            KV("Напряжение", b.voltageV?.let { "${fmt(it, 2)} В" } ?: NO_DATA)
            KV("Состояние", b.healthText, if (b.healthBad) C.bad.fg else C.text)
            KV("Циклы", b.cycles?.toString() ?: NO_DATA)
            KV("Зарядка", b.pluggedText)
        }
    }
}
