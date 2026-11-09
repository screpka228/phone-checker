package com.phonechecker.checks

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phonechecker.KeyBus
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Item
import com.phonechecker.data.Status
import com.phonechecker.ui.C
import com.phonechecker.ui.Card
import com.phonechecker.ui.Header
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import com.phonechecker.ui.T
import com.phonechecker.ui.Tone
import kotlin.math.abs

/** Состояние строки. WAIT — датчик есть, ждём отклика; ASK — спрашиваем пользователя «было?». */
enum class RowState { MISSING, WAIT, HINT, ASK, OK, DEFECT }

data class SRow(val state: RowState, val text: String)

private enum class S(val title: String) {
    ACCEL("Акселерометр"), GYRO("Гироскоп"), ROTATE("Автоповорот"), MAG("Магнитометр (компас)"),
    BARO("Барометр"), PROX("Приближение"), LIGHT("Освещённость"), HALL("Датчик Холла"),
    STEP("Шагомер"), IR("ИК-порт"), VIBRO("Вибромотор"), FINGER("Отпечаток"), KEYS("Кнопки громк./питания"),
}

/** Сообщения Samsung о магните чехла (разные версии One UI). */
private val COVER_ACTIONS = listOf(
    "com.samsung.cover.OPEN", "com.samsung.cover.STATE_CHANGED", "com.samsung.android.cover.STATE_CHANGED",
    "com.samsung.flip.OPEN", "com.sec.android.cover.ATTACH_STATE_CHANGED",
)

private fun missing() = SRow(RowState.MISSING, "нет в телефоне")

@Composable
fun SensorsCheck(index: String, onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val pm = ctx.packageManager
    val rows = remember { mutableStateMapOf<S, SRow>() }
    fun set(s: S, r: SRow) { if (rows[s]?.state != RowState.DEFECT) rows[s] = r }
    fun ok(s: S, text: String = "работает") = set(s, SRow(RowState.OK, text))

    // Холл: публичного типа нет, ищем датчик по имени (Samsung, Xiaomi и др.).
    val hall = remember { sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { "hall" in it.name.lowercase() } }
    // У Samsung Холл — не датчик, а устройство ввода «hall»; события приходят широковещательно от системы чехлов.
    val hallInput = remember {
        InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.name?.lowercase()?.contains("hall") == true }
    }
    val ir = remember { ctx.getSystemService(ConsumerIrManager::class.java)?.takeIf { it.hasIrEmitter() } }
    val vib = remember { ctx.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() } }
    val hasFinger = remember { pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) }

    val stepPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Начальное состояние строк.
    LaunchedEffect(Unit) {
        fun init(s: S, present: Boolean, hint: String, yellow: Boolean = false) {
            rows[s] = if (present) SRow(if (yellow) RowState.HINT else RowState.WAIT, hint) else missing()
        }
        init(S.ACCEL, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null, "подвигайте телефон")
        init(S.GYRO, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null, "покрутите телефон")
        init(S.ROTATE, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null, "поверните боком")
        init(S.MAG, sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null, "покрутите телефон")
        init(S.BARO, sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null, "ждём данные")
        init(S.PROX, sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null, "поднесите руку", yellow = true)
        init(S.LIGHT, sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null, "прикройте датчик")
        init(S.HALL, hall != null || hallInput, "поднесите магнит")
        init(S.STEP, sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null || sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null, "пройдитесь")
        init(S.IR, ir != null, "нажмите")
        init(S.VIBRO, vib != null, "нажмите")
        init(S.FINGER, hasFinger, "нажмите и приложите палец")
        init(S.KEYS, true, "нажмите каждую")
        if (Build.VERSION.SDK_INT >= 29 && rows[S.STEP]?.state != RowState.MISSING &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) stepPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    // Датчики: «работает», когда данные заметно изменились относительно первых.
    DisposableEffect(Unit) {
        val first = HashMap<Int, FloatArray>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.sensor.type
                val v = e.values
                val f = first.getOrPut(System.identityHashCode(e.sensor)) { v.clone() }
                val delta = v.indices.maxOfOrNull { abs(v[it] - f[it]) } ?: 0f
                when {
                    e.sensor == hall -> if (delta > 0f) ok(S.HALL, "работает")
                    t == Sensor.TYPE_ACCELEROMETER -> if (delta > 1f) ok(S.ACCEL)
                    t == Sensor.TYPE_GYROSCOPE -> if (delta > 0.5f) ok(S.GYRO)
                    t == Sensor.TYPE_MAGNETIC_FIELD -> if (delta > 5f) {
                        if (e.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) set(S.MAG, SRow(RowState.OK, "калибровка"))
                        else ok(S.MAG)
                    }
                    t == Sensor.TYPE_PRESSURE -> ok(S.BARO, "${v[0].toInt()} гПа")
                    t == Sensor.TYPE_PROXIMITY -> if (delta > 0f) ok(S.PROX)
                    t == Sensor.TYPE_LIGHT -> if (delta > 5f || rows[S.LIGHT]?.state == RowState.OK) ok(S.LIGHT, "${v[0].toInt()} лк")
                    t == Sensor.TYPE_STEP_DETECTOR -> ok(S.STEP)
                    t == Sensor.TYPE_STEP_COUNTER -> if (delta > 0f) ok(S.STEP)
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val types = listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PRESSURE,
            Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER,
        )
        types.mapNotNull { sm.getDefaultSensor(it) }.plus(listOfNotNull(hall))
            .forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }

        val orient = object : OrientationEventListener(ctx) {
            var start = -1
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val bucket = ((o + 45) / 90) % 4
                if (start < 0) start = bucket else if (bucket != start) ok(S.ROTATE)
            }
        }
        if (orient.canDetectOrientation()) orient.enable()

        // Кнопки: громкость ловим через Activity, питание — по выключению экрана.
        val pressed = linkedSetOf<String>()
        fun key(name: String) {
            pressed += name
            val all = pressed.size == 3
            set(S.KEYS, SRow(if (all) RowState.OK else RowState.WAIT, if (all) "все работают" else "нажато: ${pressed.joinToString(" ")}"))
        }
        KeyBus.listener = { ev ->
            when (ev.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { if (ev.action == KeyEvent.ACTION_DOWN) key("＋"); true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { if (ev.action == KeyEvent.ACTION_DOWN) key("−"); true }
                else -> false
            }
        }
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { key("⏻") }
        }
        ctx.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        val cover = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { ok(S.HALL) }
        }
        val coverFilter = IntentFilter().apply { COVER_ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(ctx, cover, coverFilter, ContextCompat.RECEIVER_EXPORTED)

        onDispose {
            sm.unregisterListener(listener)
            orient.disable()
            KeyBus.listener = null
            ctx.unregisterReceiver(screenOff)
            ctx.unregisterReceiver(cover)
        }
    }

    val cancel = remember { CancellationSignal() }
    DisposableEffect(Unit) { onDispose { cancel.cancel() } }

    fun tap(s: S) {
        val r = rows[s] ?: return
        when (s) {
            S.VIBRO -> {
                vib?.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
                rows[s] = SRow(RowState.ASK, "вибрировало?")
            }
            S.IR -> {
                // NEC-подобная посылка на 38 кГц. Увидеть вспышку можно через камеру другого телефона.
                runCatching { ir?.transmit(38000, IntArray(68) { if (it % 2 == 0) 560 else 1690 }) }
                    .onSuccess { rows[s] = SRow(RowState.OK, "сигнал отправлен") }
                    .onFailure { rows[s] = SRow(RowState.DEFECT, "ошибка передачи") }
            }
            S.FINGER -> if (r.state != RowState.OK) startFingerprint(ctx, cancel) { st, text -> rows[s] = SRow(st, text) }
            else -> when (r.state) {
                // Тап по ждущей строке помечает датчик как неисправный, повторный — снимает отметку.
                RowState.WAIT, RowState.HINT -> rows[s] = SRow(RowState.DEFECT, "не работает")
                RowState.DEFECT -> rows[s] = SRow(RowState.WAIT, "ждём отклика")
                else -> {}
            }
        }
    }

    Page(bottom = {
        PrimaryButton("Далее") { onResult(sensorsResult(rows)) }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header(index, "Датчики", "Список строится по тому, что есть в телефоне. Строки загораются сами, когда датчик откликнулся. Тап по серой строке — отметить «не работает».")
        Card(gap = 8) {
            S.entries.forEach { s ->
                val r = rows[s] ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = r.state != RowState.MISSING) { tap(s) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    T(s.title, 15.sp, modifier = Modifier.weight(1f))
                    if (r.state == RowState.ASK) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(r.text, 12.sp, C.text2)
                            Chip("да", C.ok) { rows[s] = SRow(RowState.OK, "работает") }
                            Chip("нет", C.bad) { rows[s] = SRow(RowState.DEFECT, "не работает") }
                        }
                    } else Chip(r.text, rowTone(r.state), null)
                }
            }
        }
    }
}

@Composable
fun rowTone(s: RowState): Tone = when (s) {
    RowState.OK -> Tone(C.ok.fg, C.track)
    RowState.HINT -> Tone(C.warn.fg, C.track)
    RowState.DEFECT -> Tone(C.bad.fg, C.track)
    else -> Tone(C.text2, C.track)
}

/** Бейдж-«пилюля» как в макете; с onClick — кнопка. */
@Composable
fun Chip(text: String, tone: Tone, onClick: (() -> Unit)?) {
    Box(
        Modifier.clip(CircleShape).background(if (onClick != null) tone.bg else tone.bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { T(text, 12.sp, tone.fg, FontWeight.SemiBold) }
}

private fun sensorsResult(rows: Map<S, SRow>): CheckResult {
    val items = S.entries.mapNotNull { s ->
        val r = rows[s] ?: return@mapNotNull null
        val st = when (r.state) {
            RowState.OK -> Status.OK
            RowState.DEFECT -> Status.DEFECT
            RowState.MISSING -> Status.INFO
            else -> Status.SKIPPED
        }
        Item(s.title, st, if (st == Status.SKIPPED) "пропущено" else r.text)
    }
    val defects = items.count { it.status == Status.DEFECT }
    val skipped = items.count { it.status == Status.SKIPPED }
    val status = when {
        defects > 0 -> Status.DEFECT
        skipped > 0 -> Status.WARN
        else -> Status.OK
    }
    val badge = when {
        defects > 0 -> "не работает: $defects"
        skipped > 0 -> "не проверено: $skipped"
        else -> "норм"
    }
    return CheckResult(status, badge, items = items)
}

/** Сканер отпечатка: любое касание (даже чужой палец) доказывает, что сенсор читает. */
private fun startFingerprint(ctx: Context, cancel: CancellationSignal, out: (RowState, String) -> Unit) {
    out(RowState.HINT, "приложите палец")
    if (Build.VERSION.SDK_INT >= 28) {
        val prompt = BiometricPrompt.Builder(ctx)
            .setTitle("Проверка сканера отпечатка")
            .setDescription("Приложите любой палец. Чужой палец тоже подойдёт: проверяем, что сенсор читает.")
            .setNegativeButton("Отмена", ctx.mainExecutor) { _, _ -> out(RowState.WAIT, "нажмите и приложите палец") }
            .build()
        prompt.authenticate(cancel, ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) = out(RowState.OK, "работает")
            override fun onAuthenticationFailed() = out(RowState.OK, "читает (палец не тот)")
            override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                when (code) {
                    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> out(RowState.HINT, "нет отпечатков в системе")
                    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT -> out(RowState.DEFECT, "сенсор недоступен")
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> out(RowState.WAIT, "нажмите и приложите палец")
                    else -> out(RowState.HINT, msg?.toString() ?: "ошибка")
                }
            }
        })
    } else {
        @Suppress("DEPRECATION")
        val fm = ctx.getSystemService(FingerprintManager::class.java)
        @Suppress("DEPRECATION")
        if (fm == null || !fm.isHardwareDetected) { out(RowState.DEFECT, "сенсор недоступен"); return }
        @Suppress("DEPRECATION")
        if (!fm.hasEnrolledFingerprints()) { out(RowState.HINT, "нет отпечатков в системе"); return }
        @Suppress("DEPRECATION")
        fm.authenticate(null, cancel, 0, object : FingerprintManager.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: FingerprintManager.AuthenticationResult?) = out(RowState.OK, "работает")
            override fun onAuthenticationFailed() = out(RowState.OK, "читает (палец не тот)")
        }, null)
    }
}
