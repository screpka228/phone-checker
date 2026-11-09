package com.phonechecker.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Status(val label: String) {
    NONE("не проверено"),
    OK("норм"),
    WARN("замечания"),
    DEFECT("дефект"),
    SKIPPED("пропущено"),
    INFO("инфо"),
}

enum class CheckId(val title: String, val hint: String, val extra: Boolean = false) {
    SCREEN("Экран", "не проверено"),
    BATTERY("Батарея", "не проверено"),
    MEMORY("Память", "не проверено"),
    SENSORS("Датчики", "не проверено"),
    CONNECT("Связь", "Wi-Fi, BT, GPS, NFC"),
    SYSTEM("Система", "инфо"),
    CAMERA("Камеры", "видео, зум, микрофон", extra = true),
    CHARGE("Зарядка", "ток и мощность", extra = true),
    STRESS("Стресс-тест", "нагрев за 2 мин", extra = true),
    AUTH("Подлинность", "модель vs железо", extra = true),
}

/** Одна строка отчёта: отдельный датчик, модуль связи и т.п. */
data class Item(val name: String, val status: Status, val value: String = "")

data class CheckResult(
    val status: Status,
    /** Короткий текст на плитке и в бейдже отчёта, например «износ 18%». */
    val badge: String = status.label,
    /** Подробности (ключ → значение) для файла отчёта. */
    val details: List<Pair<String, String>> = emptyList(),
    /** Подпункты, которые в отчёте идут отдельными строками. */
    val items: List<Item> = emptyList(),
)

/** Состояние всей проверки + сохраняемые настройки. Живёт пока жив процесс. */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dark by mutableStateOf(prefs.getBoolean("dark", true))
        private set

    fun toggleTheme() {
        dark = !dark
        prefs.edit().putBoolean("dark", dark).apply()
    }

    private val enabled = mutableStateMapOf<CheckId, Boolean>().apply {
        CheckId.entries.filter { it.extra }.forEach { put(it, prefs.getBoolean("extra_${it.name}", false)) }
    }

    fun isEnabled(id: CheckId) = !id.extra || enabled[id] == true

    fun setEnabled(id: CheckId, on: Boolean) {
        enabled[id] = on
        prefs.edit().putBoolean("extra_${id.name}", on).apply()
    }

    val activeChecks: List<CheckId> get() = CheckId.entries.filter { isEnabled(it) }

    val results = mutableStateMapOf<CheckId, CheckResult>()

    fun result(id: CheckId): CheckResult = results[id] ?: CheckResult(Status.NONE, id.hint)

    fun set(id: CheckId, r: CheckResult) { results[id] = r }

    fun skip(id: CheckId) { results[id] = CheckResult(Status.SKIPPED) }

    val doneCount: Int get() = activeChecks.count { results[it] != null }

    var reportTreeUri: String?
        get() = prefs.getString("report_tree", null)
        set(v) { prefs.edit().putString("report_tree", v).apply() }
}
