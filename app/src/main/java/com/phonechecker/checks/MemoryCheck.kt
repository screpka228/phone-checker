package com.phonechecker.checks

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val GB = 1_000_000_000L
private const val GIB = 1L shl 30
private const val BLOCK = 4 shl 20 // 4 МБ

/** Маркетинговый объём: ближайшая степень двойки (32, 64, 128 ГБ…), не меньше реального. */
fun declaredGb(bytes: Long): Long {
    var g = 8L
    while (g * GB < bytes) g *= 2
    return g
}

fun gbText(bytes: Long): String = "${fmt(bytes / GB.toFloat(), if (bytes < 10 * GB) 1 else 0)} ГБ"

data class StorageInfo(val total: Long, val free: Long, val ramTotal: Long)

fun readStorage(ctx: Context): StorageInfo {
    val st = StatFs(Environment.getDataDirectory().path)
    val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    return StorageInfo(st.totalBytes, st.availableBytes, mi.totalMem)
}

/** ОЗУ округляем до «паспортного» значения (часть памяти занимает ядро). */
fun ramMarketingGb(bytes: Long): Int {
    val g = bytes / GIB.toFloat()
    return listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32).firstOrNull { it >= g - 0.05f } ?: g.toInt()
}

data class RwResult(val bytes: Long, val ok: Boolean, val writeMBs: Float, val readMBs: Float)

/** Пишет файл псевдослучайных блоков, читает и сверяет CRC каждого блока. Файл удаляется. */
suspend fun rwTest(dir: File, size: Long, progress: (Float, Long) -> Unit): RwResult = withContext(Dispatchers.IO) {
    val f = File(dir, "rw_test.bin")
    val blocks = (size / BLOCK).toInt()
    val buf = ByteArray(BLOCK)
    val crcs = LongArray(blocks)
    try {
        val t0 = System.nanoTime()
        FileOutputStream(f).use { out ->
            for (b in 0 until blocks) {
                coroutineContext.ensureActive()
                Random(b * 7919L + 17).nextBytes(buf)
                crcs[b] = CRC32().apply { update(buf) }.value
                out.write(buf)
                if (b % 16 == 15) out.fd.sync()
                progress((b + 1f) / blocks / 2, (b + 1L) * BLOCK)
            }
            out.fd.sync()
        }
        val tw = (System.nanoTime() - t0) / 1e9f
        var ok = true
        val t1 = System.nanoTime()
        FileInputStream(f).use { inp ->
            for (b in 0 until blocks) {
                coroutineContext.ensureActive()
                var n = 0
                while (n < BLOCK) {
                    val r = inp.read(buf, n, BLOCK - n)
                    if (r < 0) break
                    n += r
                }
                if (n != BLOCK || CRC32().apply { update(buf) }.value != crcs[b]) ok = false
                progress(0.5f + (b + 1f) / blocks / 2, blocks.toLong() * BLOCK)
            }
        }
        val tr = (System.nanoTime() - t1) / 1e9f
        val total = blocks.toLong() * BLOCK
        RwResult(total, ok, total / 1e6f / tw, total / 1e6f / tr)
    } finally {
        f.delete()
    }
}

@Composable
fun MemoryCheck(index: String, onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { readStorage(ctx) }
    // До 4 ГБ, но оставляем минимум 1 ГБ свободным.
    val testSize = remember { minOf(4 * GB, s.free - GB).coerceAtLeast(0) }
    var prog by remember { mutableFloatStateOf(0f) }
    var written by remember { mutableStateOf(0L) }
    var res by remember { mutableStateOf<RwResult?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (testSize < 256L * 1_000_000) { err = "мало места для теста"; return@LaunchedEffect }
        try {
            res = rwTest(ctx.filesDir, testSize) { p, w -> prog = p; written = w }
        } catch (e: java.io.IOException) {
            err = "ошибка записи: ${e.message}"
        }
    }

    val r = res
    val declared = declaredGb(s.total)
    val status = when {
        r != null && !r.ok -> Status.DEFECT
        r == null -> Status.WARN
        else -> Status.OK
    }
    val badge = when {
        r != null && !r.ok -> "данные искажены"
        r == null -> "тест не завершён"
        else -> "норм"
    }

    Page(bottom = {
        PrimaryButton("Далее", enabled = r != null || err != null) {
            onResult(
                CheckResult(
                    status, badge,
                    details = listOfNotNull(
                        "Заявлено" to "$declared ГБ",
                        "Доступно системе" to gbText(s.total),
                        "Свободно" to gbText(s.free),
                        "ОЗУ" to "${ramMarketingGb(s.ramTotal)} ГБ (${gbText(s.ramTotal)} видно системе)",
                        r?.let { "Тест записи/чтения" to "${gbText(it.bytes)}, контрольная сумма ${if (it.ok) "совпала" else "НЕ совпала"}" },
                        r?.let { "Скорость записи" to "${it.writeMBs.toInt()} МБ/с" },
                        r?.let { "Скорость чтения" to "${it.readMBs.toInt()} МБ/с" },
                        err?.let { "Тест" to it },
                    ),
                ),
            )
        }
        SecondaryButton("Пропустить", onClick = onSkip)
    }) {
        Header("$index · автоматически", "Память", "Записываем и читаем тестовый файл, чтобы поймать подделку объёма.")
        Card {
            Cap("Тест записи и чтения")
            Bar(if (r != null) 1f else prog, if (r != null && !r.ok) C.bad.fg else C.accent)
            when {
                err != null -> KV("Тест", err!!, C.warn.fg)
                r == null && prog < 0.5f -> KV("Записано", "${gbText(written)} из ${gbText(testSize)}")
                r == null -> KV("Проверяем", "чтение и контрольная сумма")
                r.ok -> KV("Контрольная сумма", "совпала ✓", C.ok.fg)
                else -> KV("Контрольная сумма", "не совпала ✗", C.bad.fg)
            }
        }
        Card {
            KV("Заявлено", "$declared ГБ")
            KV("Доступно системе", gbText(s.total))
            KV("Свободно", gbText(s.free))
            KV("ОЗУ", "${ramMarketingGb(s.ramTotal)} ГБ")
            KV("Скорость записи", r?.let { "${it.writeMBs.toInt()} МБ/с" } ?: "…")
            KV("Скорость чтения", r?.let { "${it.readMBs.toInt()} МБ/с" } ?: "…")
        }
    }
}
