package com.phonechecker.checks

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Item
import com.phonechecker.data.Status
import com.phonechecker.ui.C
import com.phonechecker.ui.Cap
import com.phonechecker.ui.Card
import com.phonechecker.ui.Header
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import com.phonechecker.ui.T

private fun neededPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

private fun granted(ctx: Context) = neededPermissions().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun ConnectCheck(index: String, onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    var perms by remember { mutableStateOf(granted(ctx)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = granted(ctx)
        asked = true
    }
    // Возврат из системных настроек (включили Wi-Fi/NFC/геолокацию) — перечитать состояние.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { resumes++; perms = granted(ctx) } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val rows = remember { androidx.compose.runtime.mutableStateMapOf<String, SRow>() }
    val actions = remember { HashMap<String, () -> Unit>() }

    if (perms) RadioProbes(ctx, resumes, rows, actions)
    Nfc(ctx, resumes, rows, actions)
    Sim(ctx, rows)

    val order = listOf("Wi-Fi", "Bluetooth", "GPS", "NFC: модуль", "NFC: чтение", "SIM-лоток")

    Page(bottom = {
        PrimaryButton("Далее") { onResult(connectResult(order, rows)) }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("$index · без SIM", "Связь", "Модули включаются по очереди, ищем сети и устройства рядом. Тап по строке с подсказкой открывает настройки.")
        if (!perms) Card {
            Cap("Нужны разрешения")
            T(
                "Android выдаёт список Wi-Fi-сетей, Bluetooth-устройств и спутников только с доступом к местоположению и «устройствам рядом». " +
                    "Приложение работает без интернета и никуда ничего не отправляет.",
                14.sp, C.text2, lineHeight = 20.sp,
            )
            PrimaryButton(if (asked) "Запросить снова" else "Разрешить", height = 48, size = 15.sp) { launcher.launch(neededPermissions()) }
            if (asked) SecondaryButton("Открыть настройки приложения") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)))
            }
        }
        Card {
            order.forEach { k ->
                val r = rows[k] ?: SRow(RowState.WAIT, if (perms) "…" else "нет разрешения")
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = actions[k] != null) { actions[k]?.invoke() }.padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    T(k, 15.sp, modifier = Modifier.weight(1f))
                    Chip(r.text, rowTone(r.state), null)
                }
            }
        }
        Card {
            Cap("NFC без карты")
            T(
                "Проверяем, что модуль есть и включается. Если под рукой второй телефон с NFC, приложите их спинками: приложение увидит его без карты.",
                14.sp, C.text2, lineHeight = 20.sp,
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun RadioProbes(ctx: Context, resumes: Int, rows: MutableMap<String, SRow>, actions: MutableMap<String, () -> Unit>) {
    val btEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    // Wi-Fi
    DisposableEffect(resumes) {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        var rec: BroadcastReceiver? = null
        if (wm == null) rows["Wi-Fi"] = SRow(RowState.MISSING, "нет в телефоне")
        else if (!wm.isWifiEnabled) {
            rows["Wi-Fi"] = SRow(RowState.HINT, "включите Wi-Fi")
            actions["Wi-Fi"] = {
                ctx.startActivity(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
            }
        } else {
            actions.remove("Wi-Fi")
            fun show() {
                val n = wm.scanResults.map { it.BSSID }.distinct().size
                if (n > 0) rows["Wi-Fi"] = SRow(RowState.OK, "найдено ${plural(n, "сеть", "сети", "сетей")}")
                else if (rows["Wi-Fi"]?.state != RowState.OK) rows["Wi-Fi"] = SRow(RowState.WAIT, "ищем сети…")
            }
            rec = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = show() }
            ctx.registerReceiver(rec, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            @Suppress("DEPRECATION") wm.startScan()
            show()
        }
        onDispose { rec?.let { ctx.unregisterReceiver(it) } }
    }

    // Bluetooth: классический поиск + BLE, считаем уникальные адреса.
    DisposableEffect(resumes) {
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val found = HashSet<String>()
        var rec: BroadcastReceiver? = null
        var le: ScanCallback? = null
        fun show() {
            rows["Bluetooth"] = if (found.isEmpty()) SRow(RowState.WAIT, "ищем устройства…")
            else SRow(RowState.OK, "найдено ${plural(found.size, "устройство", "устройства", "устройств")}")
        }
        when {
            ad == null -> rows["Bluetooth"] = SRow(RowState.MISSING, "нет в телефоне")
            !ad.isEnabled -> {
                rows["Bluetooth"] = SRow(RowState.HINT, "включите Bluetooth")
                actions["Bluetooth"] = { btEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
            else -> {
                actions.remove("Bluetooth")
                show()
                rec = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        @Suppress("DEPRECATION")
                        i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { found += it.address; show() }
                    }
                }
                ctx.registerReceiver(rec, IntentFilter(BluetoothDevice.ACTION_FOUND))
                runCatching { ad.startDiscovery() }
                le = object : ScanCallback() {
                    override fun onScanResult(type: Int, r: ScanResult) { found += r.device.address; show() }
                }
                runCatching { ad.bluetoothLeScanner?.startScan(le) }
            }
        }
        onDispose {
            rec?.let { ctx.unregisterReceiver(it) }
            runCatching { ad?.cancelDiscovery() }
            le?.let { cb -> runCatching { ad?.bluetoothLeScanner?.stopScan(cb) } }
        }
    }

    // GPS: число видимых спутников.
    DisposableEffect(resumes) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        var cb: GnssStatus.Callback? = null
        val ll = LocationListener { }
        when {
            lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER) -> rows["GPS"] = SRow(RowState.MISSING, "нет в телефоне")
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                rows["GPS"] = SRow(RowState.HINT, "включите геолокацию")
                actions["GPS"] = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            else -> {
                actions.remove("GPS")
                if (rows["GPS"]?.state != RowState.OK) rows["GPS"] = SRow(RowState.WAIT, "ищем спутники…")
                cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(s: GnssStatus) {
                        val used = (0 until s.satelliteCount).count { s.usedInFix(it) }
                        if (s.satelliteCount > 0) rows["GPS"] = SRow(RowState.OK, "спутники: ${s.satelliteCount}" + if (used > 0) " (в фиксе $used)" else "")
                    }
                }
                lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll, Looper.getMainLooper())
            }
        }
        onDispose {
            cb?.let { lm?.unregisterGnssStatusCallback(it) }
            lm?.removeUpdates(ll)
        }
    }
}

@Composable
private fun Nfc(ctx: Context, resumes: Int, rows: MutableMap<String, SRow>, actions: MutableMap<String, () -> Unit>) {
    DisposableEffect(resumes) {
        val activity = ctx as Activity
        val nfc = NfcAdapter.getDefaultAdapter(ctx)
        var reader = false
        when {
            nfc == null -> {
                rows["NFC: модуль"] = SRow(RowState.MISSING, "нет в телефоне")
                rows["NFC: чтение"] = SRow(RowState.MISSING, "нет в телефоне")
            }
            !nfc.isEnabled -> {
                rows["NFC: модуль"] = SRow(RowState.HINT, "есть, включите")
                rows["NFC: чтение"] = SRow(RowState.WAIT, "опционально")
                actions["NFC: модуль"] = { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
            else -> {
                actions.remove("NFC: модуль")
                rows["NFC: модуль"] = SRow(RowState.OK, "есть, включается")
                if (rows["NFC: чтение"]?.state != RowState.OK) rows["NFC: чтение"] = SRow(RowState.WAIT, "опционально")
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                nfc.enableReaderMode(activity, { tag ->
                    activity.runOnUiThread { rows["NFC: чтение"] = SRow(RowState.OK, "видит: ${tag.techList.firstOrNull()?.substringAfterLast('.') ?: "метка"}") }
                }, flags, null)
                reader = true
            }
        }
        onDispose { if (reader) runCatching { nfc?.disableReaderMode(activity) } }
    }
}

@Composable
private fun Sim(ctx: Context, rows: MutableMap<String, SRow>) {
    DisposableEffect(Unit) {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val slots = when {
            tm == null || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> 0
            Build.VERSION.SDK_INT >= 30 -> tm.activeModemCount
            else -> @Suppress("DEPRECATION") tm.phoneCount
        }
        val esim = Build.VERSION.SDK_INT >= 28 && ctx.getSystemService(EuiccManager::class.java)?.isEnabled == true
        rows["SIM-лоток"] = when {
            slots == 0 -> SRow(RowState.MISSING, "нет")
            else -> SRow(RowState.OK, "слотов: $slots" + if (esim) " + eSIM" else "")
        }
        onDispose {}
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

private fun connectResult(order: List<String>, rows: Map<String, SRow>): CheckResult {
    val items = order.map { k ->
        val r = rows[k]
        val st = when {
            r == null -> Status.SKIPPED
            k == "SIM-лоток" -> Status.INFO
            k == "NFC: чтение" && r.state != RowState.OK -> if (r.state == RowState.MISSING) Status.INFO else Status.SKIPPED
            r.state == RowState.OK -> Status.OK
            r.state == RowState.MISSING -> Status.INFO
            r.state == RowState.DEFECT -> Status.DEFECT
            else -> Status.SKIPPED
        }
        Item(k, st, if (st == Status.SKIPPED) "пропущено" else r?.text.orEmpty())
    }
    // «NFC: чтение» опционально и на итог не влияет.
    val core = items.filter { it.name != "NFC: чтение" }
    val status = when {
        core.any { it.status == Status.DEFECT } -> Status.DEFECT
        core.any { it.status == Status.SKIPPED } -> Status.WARN
        else -> Status.OK
    }
    val badge = when (status) {
        Status.WARN -> "не проверено: ${core.count { it.status == Status.SKIPPED }}"
        else -> status.label
    }
    return CheckResult(status, badge, items = items)
}
