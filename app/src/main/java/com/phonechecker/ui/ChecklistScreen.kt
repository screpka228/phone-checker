package com.phonechecker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonechecker.data.AppState
import com.phonechecker.data.CheckId
import com.phonechecker.data.Status

@Composable
fun plateTone(id: CheckId): Tone = when (id) {
    CheckId.SCREEN -> C.ok
    CheckId.BATTERY, CheckId.CHARGE -> C.warn
    CheckId.MEMORY, CheckId.AUTH -> C.info
    CheckId.SENSORS -> C.purple
    CheckId.CONNECT -> C.cyan
    CheckId.CAMERA -> C.neutral
    CheckId.STRESS -> C.badAlt
    CheckId.SYSTEM -> Tone(C.text2, C.track)
}

private val extraDesc = mapOf(
    CheckId.CAMERA to "для прошивок без штатной камеры",
    CheckId.CHARGE to "ток в мА и мощность, видно плохой разъём",
    CheckId.STRESS to "2 минуты нагрузки, следим за нагревом",
    CheckId.AUTH to "сверка модели с реальным железом",
)

@Composable
fun ChecklistScreen(state: AppState, open: (CheckId) -> Unit, report: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    // Система всегда последней плиткой, доп. проверки — перед ней.
    val tiles = state.activeChecks.sortedBy { if (it == CheckId.SYSTEM) 1 else 0 }

    Box(Modifier.fillMaxSize()) {
        Page(bottom = {
            PrimaryButton("Сформировать отчёт", onClick = report)
        }) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    T("Проверка", 26.sp, weight = FontWeight.Bold, modifier = Modifier.plainClick { menu = true })
                    T("${state.doneCount} из ${tiles.size} проверок", 13.sp, C.text2, modifier = Modifier.padding(top = 4.dp))
                }
                ThemeToggle(state.dark, state::toggleTheme)
            }
            Gap(2)
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { Tile(it, state, Modifier.weight(1f)) { open(it) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Disclaimer()
        }

        if (menu) ExtrasSheet(state) { menu = false }
    }
}

@Composable
private fun Tile(id: CheckId, state: AppState, modifier: Modifier, onClick: () -> Unit) {
    val r = state.result(id)
    val plate = plateTone(id)
    val pip = when {
        r.status == Status.NONE && id == CheckId.SYSTEM -> C.accent
        else -> C.pip(r.status)
    }
    Box(
        modifier.heightIn(min = 110.dp).clip(RoundedCornerShape(18.dp)).background(C.card)
            .border(1.dp, C.border, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(plate.bg), contentAlignment = Alignment.Center) {
                CheckIcon(id, plate.fg, plate.bg)
            }
            Column {
                T(id.title, 15.sp, weight = FontWeight.SemiBold)
                val stColor = if (r.status == Status.NONE || r.status == Status.SKIPPED) C.text2 else C.tone(r.status).fg
                T(r.badge, 12.sp, stColor)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 0.dp)) { Pip(pip) }
    }
}

@Composable
private fun ExtrasSheet(state: AppState, close: () -> Unit) {
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().background(C.scrim).plainClick(close), verticalArrangement = Arrangement.Bottom) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(C.card)
                .plainClick {}
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(C.switchOff).align(Alignment.CenterHorizontally))
            Gap(14)
            T("Дополнительные проверки", 20.sp, weight = FontWeight.Bold)
            T("Включённые появятся плитками в чеклисте", 13.sp, C.text2, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            CheckId.entries.filter { it.extra }.forEach { id ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        T(id.title, 16.sp)
                        T(extraDesc.getValue(id), 12.sp, C.text2)
                    }
                    val on = state.isEnabled(id)
                    Switch(on) { state.setEnabled(id, !on) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.border))
            }
            Gap(16)
            PrimaryButton("Готово", height = 52, size = 16.sp, onClick = close)
        }
    }
}
