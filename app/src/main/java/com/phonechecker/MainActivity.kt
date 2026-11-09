package com.phonechecker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.phonechecker.data.AppState
import com.phonechecker.data.CheckId
import com.phonechecker.ui.AppTheme
import com.phonechecker.ui.C
import com.phonechecker.ui.ChecklistScreen
import com.phonechecker.ui.Header
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton

class MainActivity : ComponentActivity() {
    private val state by lazy { (application as App).state }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme(state.dark) {
                val c = C
                LaunchedEffect(state.dark) {
                    val bar = c.bg.toArgb()
                    val style = if (state.dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar)
                    enableEdgeToEdge(style, style)
                }
                Box(Modifier.fillMaxSize().background(C.bg)) { Root(state) }
            }
        }
    }
}

/** Маршрут: имя проверки, "report" или null (чеклист). */
@Composable
private fun Root(state: AppState) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val home = { route = null }
    BackHandler(enabled = route != null, onBack = home)

    // «Далее» ведёт к следующей непроверенной проверке, а если их нет — к чеклисту.
    fun next(from: CheckId) {
        val list = state.activeChecks
        val after = list.dropWhile { it != from }.drop(1) + list.takeWhile { it != from }
        route = after.firstOrNull { state.results[it] == null }?.name
    }

    when (val r = route) {
        null -> ChecklistScreen(state, open = { route = it.name }, report = { route = "report" })
        "report" -> Placeholder("Отчёт", home)
        else -> {
            val id = CheckId.valueOf(r)
            CheckHost(id, state, done = { next(id) })
        }
    }
}

@Composable
private fun CheckHost(id: CheckId, state: AppState, done: () -> Unit) {
    val skip = { state.skip(id); done() }
    when (id) {
        else -> Page(bottom = {
            SecondaryButton("Пропустить", onClick = skip)
        }) {
            Header("Скоро", id.title, "Эта проверка появится в следующих шагах.")
        }
    }
}

@Composable
private fun Placeholder(title: String, back: () -> Unit) {
    Page(bottom = { PrimaryButton("Назад", onClick = back) }) { Header("Скоро", title) }
}
