package com.phonechecker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonechecker.data.AppState
import com.phonechecker.data.CheckId
import com.phonechecker.data.Item
import com.phonechecker.data.Status
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DISCLAIMER = "Результат информационный и не гарантирует отсутствие скрытых дефектов."

data class Group(val title: String, val rows: List<Item>)

/** Группы отчёта: основное, датчики и связь построчно, доп. проверки. */
fun buildGroups(state: AppState): List<Group> {
    fun row(id: CheckId): Item {
        val r = state.result(id)
        val badge = if (r.status == Status.NONE) Status.NONE.label else r.badge
        return Item(id.title, r.status, badge)
    }
    fun expanded(id: CheckId): List<Item> {
        val r = state.result(id)
        return r.items.ifEmpty { listOf(row(id)) }
    }
    return listOfNotNull(
        Group("Основное", listOf(CheckId.SCREEN, CheckId.BATTERY, CheckId.MEMORY, CheckId.SYSTEM).map(::row)),
        Group("Датчики", expanded(CheckId.SENSORS)),
        Group("Связь", expanded(CheckId.CONNECT)),
        CheckId.entries.filter { it.extra && state.isEnabled(it) }.takeIf { it.isNotEmpty() }
            ?.let { ids -> Group("Дополнительные проверки", ids.map(::row)) },
    )
}

private fun deviceName() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

fun reportText(state: AppState, date: Date): String = buildString {
    appendLine("Отчёт о проверке телефона")
    appendLine("Модель: ${deviceName()}")
    appendLine("Android: ${Build.VERSION.RELEASE}, патч безопасности: ${Build.VERSION.SECURITY_PATCH}")
    appendLine("Дата: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(date)}")
    appendLine()
    buildGroups(state).forEach { g ->
        appendLine("== ${g.title} ==")
        g.rows.forEach { appendLine("  [${it.status.label}] ${it.name}: ${it.value}") }
        appendLine()
    }
    appendLine("== Подробности ==")
    CheckId.entries.filter { state.isEnabled(it) }.forEach { id ->
        val r = state.result(id)
        appendLine("${id.title}: ${if (r.status == Status.NONE) "не проверено" else "${r.status.label} (${r.badge})"}")
        r.details.forEach { (k, v) -> appendLine("  $k: $v") }
        r.items.forEach { appendLine("  ${it.name}: ${it.value} [${it.status.label}]") }
    }
    appendLine()
    appendLine("Разблокированный загрузчик и root не считаются дефектом, это только информация.")
    appendLine(DISCLAIMER)
}

fun reportJson(state: AppState, date: Date): String = JSONObject().apply {
    put("device", JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("device", Build.DEVICE)
        put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT); put("securityPatch", Build.VERSION.SECURITY_PATCH)
    })
    put("date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(date))
    put("checks", JSONArray().apply {
        CheckId.entries.filter { state.isEnabled(it) }.forEach { id ->
            val r = state.result(id)
            put(JSONObject().apply {
                put("id", id.name.lowercase()); put("title", id.title)
                put("status", if (r.status == Status.NONE) "not_checked" else r.status.name.lowercase())
                put("badge", r.badge)
                put("details", JSONObject().apply { r.details.forEach { (k, v) -> put(k, v) } })
                put("items", JSONArray().apply {
                    r.items.forEach { put(JSONObject().put("name", it.name).put("status", it.status.name.lowercase()).put("value", it.value)) }
                })
            })
        }
    })
    put("disclaimer", DISCLAIMER)
}.toString(2)

/** Пишет оба файла в выбранную через SAF папку. Возвращает имя без расширения. */
fun saveReport(ctx: Context, tree: Uri, state: AppState): String {
    val date = Date()
    val model = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    val base = "report_${model}_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(date)}"
    val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    fun write(mime: String, name: String, body: String) {
        val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, mime, name) ?: error("не удалось создать $name")
        ctx.contentResolver.openOutputStream(doc)!!.use { it.write(body.toByteArray()) }
    }
    write("text/plain", "$base.txt", reportText(state, date))
    write("application/json", "$base.json", reportJson(state, date))
    return base
}

@Composable
fun ReportScreen(state: AppState, back: () -> Unit) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun trySave(tree: Uri) {
        msg = runCatching { saveReport(ctx, tree, state) }
            .fold({ "Сохранено: $it.txt и .json" to true }, { "Ошибка: ${it.message}. Выберите папку заново." to false })
        if (msg?.second == false) state.reportTreeUri = null
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            state.reportTreeUri = uri.toString()
            trySave(uri)
        }
    }

    Page(bottom = {
        msg?.let { (t, ok) -> T(t, 13.sp, if (ok) C.ok.fg else C.bad.fg) }
        PrimaryButton("Сохранить на SD-карту", height = 52, size = 16.sp) {
            val saved = state.reportTreeUri?.let(Uri::parse)
            val stillAllowed = saved != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
            if (stillAllowed) trySave(saved!!) else pick.launch(null)
        }
        if (state.reportTreeUri != null) SecondaryButton("Выбрать другую папку") { pick.launch(null) }
    }) {
        Column(Modifier.padding(top = 8.dp)) {
            Cap("Итог", C.accent)
            T("Отчёт", 26.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            T("${deviceName()} · Android ${Build.VERSION.RELEASE} · патч ${Build.VERSION.SECURITY_PATCH}", 12.sp, C.text2, modifier = Modifier.padding(top = 4.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.border, RoundedCornerShape(16.dp)),
        ) {
            buildGroups(state).forEach { g ->
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) { Cap(g.title, size = 11.sp) }
                g.rows.forEachIndexed { i, it ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Pip(C.pip(it.status))
                        T(it.name, 15.sp, modifier = Modifier.weight(1f))
                        Badge(it.value.ifEmpty { it.status.label }, C.tone(it.status))
                    }
                    if (i < g.rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(C.border))
                }
            }
        }
        T("Разблокированный загрузчик и root не считаются минусом — это только информация.", 12.sp, C.text3)
        Disclaimer()
    }
}
