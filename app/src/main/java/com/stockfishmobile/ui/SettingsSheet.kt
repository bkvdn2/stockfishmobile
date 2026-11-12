package com.stockfishmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockfishmobile.data.AppSettings
import com.stockfishmobile.engine.EngineState
import com.stockfishmobile.engine.StockfishInstaller
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    engine: EngineState,
    variant: String?,
    vm: AnalysisViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cores = remember { StockfishInstaller.cpuCount() }
    val hashOptions = remember {
        val max = StockfishInstaller.maxHashMb(context)
        listOf(16, 32, 64, 128, 256, 512, 1024).filter { it <= max }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Настройки анализа", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            SwitchRow("Бесконечный анализ", "Считать, пока не будет остановлен", settings.isInfinite) { on ->
                vm.updateSettings { it.copy(depth = if (on) 0 else 20) }
            }
            if (!settings.isInfinite) {
                StepSlider(
                    title = "Глубина анализа",
                    value = settings.depth,
                    range = 6..50,
                    label = { "$it" },
                    hint = "Чем больше глубина, тем точнее и дольше расчёт",
                ) { v -> vm.updateSettings { it.copy(depth = v) } }
            }
            StepSlider(
                title = "Число вариантов (MultiPV)",
                value = settings.multiPv,
                range = 1..5,
                label = { "$it" },
                hint = "Сколько лучших продолжений показывать",
            ) { v -> vm.updateSettings { it.copy(multiPv = v) } }
            StepSlider(
                title = "Потоки процессора",
                value = settings.threads,
                range = 1..cores,
                label = { "$it из $cores" },
                hint = "Больше потоков — быстрее, но сильнее нагрев и расход батареи",
            ) { v -> vm.updateSettings { it.copy(threads = v) } }
            if (hashOptions.size > 1) {
                val idx = hashOptions.indexOfFirst { it >= settings.hashMb }.let { if (it < 0) hashOptions.lastIndex else it }
                StepSlider(
                    title = "Хеш-таблица",
                    value = idx,
                    range = 0..hashOptions.lastIndex,
                    label = { "${hashOptions[it]} МБ" },
                    hint = "Память для таблицы транспозиций",
                ) { v -> vm.updateSettings { it.copy(hashMb = hashOptions[v]) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow("Стрелки на доске", "Показывать стрелки лучших ходов", settings.showArrows) { on ->
                vm.updateSettings { it.copy(showArrows = on) }
            }
            SwitchRow("Координаты", "Буквы и цифры по краю доски", settings.showCoordinates) { on ->
                vm.updateSettings { it.copy(showCoordinates = on) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Движок", fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(engine.engineName ?: "Stockfish", variant?.let { "сборка $it" }).joinToString(" · "),
                color = Palette.textMuted,
                fontSize = 13.sp,
            )
            Text(
                "Работает локально на устройстве, без интернета. Stockfish распространяется по лицензии GPL v3.",
                color = Palette.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = Palette.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepSlider(
    title: String,
    value: Int,
    range: IntRange,
    label: (Int) -> String,
    hint: String,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 15.sp)
            Text(label(current.roundToInt()), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent)
        }
        if (range.last > range.first) {
            Slider(
                value = current,
                onValueChange = { current = it },
                onValueChangeFinished = { onCommit(current.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0),
            )
        }
        Text(hint, fontSize = 12.sp, color = Palette.textMuted)
    }
}
