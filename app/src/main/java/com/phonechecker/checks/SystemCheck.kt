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

fun systemInfo(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager

    val tags = Build.TAGS ?: ""
    val customSigns = buildList {
        if ("test-keys" in tags || "dev-keys" in tags) add("подпись $tags")
        if (Build.TYPE != "user") add("сборка ${Build.TYPE}")
        listOf("ro.lineage.version" to "LineageOS", "ro.crdroid.version" to "crDroid", "ro.pixelexperience.version" to "PixelExperience", "ro.modversion" to "мод")
            .forEach { (k, n) -> if (prop(k).isNotEmpty()) add(n) }
    }
    val firmware = if (customSigns.isEmpty()) "стоковая" else "кастомная (${customSigns.joinToString()})"

    val vbs = prop("ro.boot.verifiedbootstate")
    val locked = prop("ro.boot.flash.locked")
    val devState = prop("ro.boot.vbmeta.device_state")
    val bootloader = when {
        devState == "unlocked" || locked == "0" || vbs == "orange" -> "разблокирован"
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
