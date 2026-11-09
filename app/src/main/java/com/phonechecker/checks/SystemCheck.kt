package com.phonechecker.checks

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Status
import com.phonechecker.ui.Card
import com.phonechecker.ui.Header
import com.phonechecker.ui.KV
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import java.io.File

/** Чтение системных свойств (getprop) через скрытый android.os.SystemProperties. */
fun prop(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
}.getOrDefault("")

private val ROOT_PACKAGES = listOf(
    "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
    "me.weishu.kernelsu", "me.bmax.apatch", "eu.chainfire.supersu", "com.koushikdutta.superuser",
)

private val SU_PATHS = listOf(
    "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/vendor/bin/su",
    "/su/bin/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su", "/debug_ramdisk/su",
)

private val CUSTOM_ROMS = listOf(
    "unica" to "UN1CA", "lineage" to "LineageOS", "crdroid" to "crDroid", "pixelexperience" to "PixelExperience",
    "evolution" to "Evolution X", "aospa" to "Paranoid Android", "arrow" to "ArrowOS", "havoc" to "Havoc",
    "pixelos" to "PixelOS", "derp" to "DerpFest", "rising" to "RisingOS", "yaap" to "YAAP", "ponces" to "GSI",
    "phh" to "GSI (phh)", "cherish" to "CherishOS", "superior" to "SuperiorOS", "ex.firmware" to "мод",
)

/** Все системные свойства (getprop доступен обычным приложениям). */
fun allProps(): Map<String, String> = runCatching {
    val re = Regex("""^\[(.+?)]: \[(.*)]$""")
    Runtime.getRuntime().exec("getprop").inputStream.bufferedReader().readLines()
        .mapNotNull { re.find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
}.getOrDefault(emptyMap())

/**
 * Порт прошивки с другой модели (например, One UI от S22 на S8): модель в разных
 * разделах или в версии загрузчика Samsung не совпадает. null — расхождений нет.
 */
fun portedFrom(): String? {
    val models = listOf("ro.product.model", "ro.product.system.model", "ro.product.vendor.model", "ro.product.odm.model", "ro.product.product.model", "ro.product.system_ext.model")
        .map(::prop).plus(Build.MODEL).filter { it.isNotBlank() }.map { it.uppercase() }.distinct()
    if (models.size > 1) return "порт: ${models.joinToString(" / ")}"
    // У Samsung версия загрузчика начинается с кода модели без «SM-»: G950FXXU…
    val bl = (Build.BOOTLOADER ?: "").uppercase()
    val code = Build.MODEL.uppercase().removePrefix("SM-")
    if (Build.MANUFACTURER.equals("samsung", true) && bl.length > 6 && code.length >= 4 && !bl.startsWith(code.take(4))) {
        return "загрузчик ${bl.take(5)}… не от ${Build.MODEL}"
    }
    return null
}

fun systemInfo(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager

    val tags = Build.TAGS ?: ""
    val props = allProps()
    val customSigns = buildList {
        if ("test-keys" in tags || "dev-keys" in tags) add("подпись $tags")
        if (Build.TYPE != "user") add("сборка ${Build.TYPE}")
        // Свойства, которые оставляют известные кастомные прошивки.
        CUSTOM_ROMS.forEach { (key, name) -> if (props.keys.any { key in it }) add(name) }
        if (prop("ro.modversion").isNotEmpty()) add("мод")
        portedFrom()?.let { add(it) }
    }.distinct()
    val custom = customSigns.isNotEmpty()
    val firmware = if (!custom) "стоковая" else "кастомная (${customSigns.joinToString()})"

    val vbs = prop("ro.boot.verifiedbootstate")
    val locked = prop("ro.boot.flash.locked")
    val devState = prop("ro.boot.vbmeta.device_state")
    val bootloader = when {
        devState == "unlocked" || locked == "0" || vbs == "orange" -> "разблокирован"
        // Кастомная прошивка на заблокированном загрузчике не ставится — значит, статус подменён.
        custom -> "скорее всего разблокирован (система сообщает «заблокирован»)"
        devState == "locked" || locked == "1" || vbs == "green" -> "заблокирован"
        else -> NO_DATA
    } + if (vbs.isNotEmpty()) " ($vbs)" else ""

    val rootSigns = buildList {
        SU_PATHS.firstOrNull { File(it).exists() }?.let { add("su: $it") }
        ROOT_PACKAGES.forEach { p ->
            if (runCatching { pm.getPackageInfo(p, 0) }.isSuccess) add(p)
        }
        if ("test-keys" in tags) add("test-keys")
    }
    val root = if (rootSigns.isEmpty()) "не найден" else "есть признаки: ${rootSigns.joinToString()}"

    val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    val admins = dpm.activeAdmins.orEmpty().map { cn ->
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(cn.packageName, 0)).toString() }.getOrDefault(cn.packageName)
    }.distinct()

    return listOf(
        "Модель" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
        "Устройство" to "${Build.DEVICE} / ${Build.PRODUCT}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Патч безопасности" to Build.VERSION.SECURITY_PATCH.ifEmpty { NO_DATA },
        "Сборка" to Build.DISPLAY,
        "Прошивка" to firmware,
        "Загрузчик" to bootloader,
        "Root" to root,
        "Админ-приложения" to if (admins.isEmpty()) "нет" else admins.joinToString(),
    )
}

@Composable
fun SystemCheck(onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val info = remember { systemInfo(ctx) }
    Page(bottom = {
        PrimaryButton("Далее") { onResult(CheckResult(Status.INFO, "инфо", details = info)) }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("Информация · не влияет на итог", "Система", "Разблокированный загрузчик и root показываются, но не считаются минусом.")
        Card { info.filter { it.first != "Устройство" && it.first != "Сборка" }.forEach { (k, v) -> KV(k, v) } }
    }
}
