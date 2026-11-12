package com.stockfishmobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockfishmobile.chess.OutcomeReason
import com.stockfishmobile.chess.PieceType
import com.stockfishmobile.chess.Side
import com.stockfishmobile.data.AppSettings
import com.stockfishmobile.engine.EngineState
import com.stockfishmobile.engine.EngineStatus
import java.io.IOException
import java.time.LocalDate

@Composable
fun MainScreen(vm: AnalysisViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var confirmNewGame by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text != null) vm.importText(text) else vm.showMessage("Не удалось открыть файл")
            } catch (e: Exception) {
                vm.showMessage("Ошибка чтения файла: ${e.message}")
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-chess-pgn")) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportPgn().toByteArray()) }
                vm.showMessage("PGN сохранён")
            } catch (e: IOException) {
                vm.showMessage("Ошибка сохранения: ${e.message}")
            }
        }
    }

    val actions = MenuActions(
        onNewGame = { confirmNewGame = true },
        onOpenFile = { openFile.launch(arrayOf("*/*")) },
        onPaste = { showImport = true },
        onShare = { sharePgn(context, vm.exportPgn()) },
        onSave = { saveFile.launch("game-${LocalDate.now()}.pgn") },
        onCopyPgn = { copy(context, "PGN", vm.exportPgn()); vm.showMessage("PGN скопирован") },
        onCopyFen = { copy(context, "FEN", vm.currentFen()); vm.showMessage("FEN скопирован") },
        onSettings = { showSettings = true },
    )

    Scaffold(
        containerColor = Palette.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val preview = ui.preview
            if (preview != null) {
                PreviewBar(preview, vm)
            } else {
                ControlBar(ui, settings, vm, actions)
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val landscape = maxWidth > maxHeight
            val boardSide = maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxHeight().width(boardSide)) {
                        EvalBar(analysis, ui, engine)
                        BoardArea(ui, settings, analysis, vm, Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        InfoColumn(ui, settings, engine, analysis, vm)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EvalBar(analysis, ui, engine)
                    BoardArea(ui, settings, analysis, vm, Modifier.fillMaxWidth())
                    InfoColumn(ui, settings, engine, analysis, vm)
                }
            }
        }
    }

    ui.promotion?.let { req ->
        PromotionDialog(req.side, onPick = { vm.onPromotionChosen(it) })
    }
    ui.pgnChoices?.let { games ->
        AlertDialog(
            onDismissRequest = vm::dismissPgnChoices,
            title = { Text("Выберите партию (${games.size})") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    games.forEachIndexed { i, g ->
                        Text(
                            "${i + 1}. ${g.title}" + (g.tags["Event"]?.let { "\n    $it" } ?: ""),
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.loadPgnGame(g) }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissPgnChoices) { Text("Отмена") } },
        )
    }
    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { showImport = false; vm.importText(it) },
            onOpenFile = { showImport = false; openFile.launch(arrayOf("*/*")) },
        )
    }
    if (confirmNewGame) {
        AlertDialog(
            onDismissRequest = { confirmNewGame = false },
            title = { Text("Новая партия?") },
            text = { Text("Текущая партия будет заменена начальной позицией. Сохраните её в PGN, если она нужна.") },
            confirmButton = { TextButton(onClick = { confirmNewGame = false; vm.newGame() }) { Text("Начать") } },
            dismissButton = { TextButton(onClick = { confirmNewGame = false }) { Text("Отмена") } },
        )
    }
    if (showSettings) {
        SettingsSheet(settings, engine, ui.engineVariant, vm, onDismiss = { showSettings = false })
    }
}

private data class MenuActions(
    val onNewGame: () -> Unit,
    val onOpenFile: () -> Unit,
    val onPaste: () -> Unit,
    val onShare: () -> Unit,
    val onSave: () -> Unit,
    val onCopyPgn: () -> Unit,
    val onCopyFen: () -> Unit,
    val onSettings: () -> Unit,
)

// ---------------------------------------------------------------------------- board

@Composable
private fun BoardArea(ui: UiState, settings: AppSettings, analysis: AnalysisView?, vm: AnalysisViewModel, modifier: Modifier) {
    val preview = ui.preview
    val position = preview?.position ?: ui.game.position
    val arrows = ArrayList<BoardArrow>()
    val highlights = HashMap<Int, Color>()
    if (preview != null) {
        preview.nextMove?.let { arrows.add(BoardArrow(it.from, it.to, Palette.previewArrow)) }
    } else if (ui.outcome == null) {
        val lines = analysis?.lines.orEmpty()
        lines.firstOrNull()?.moves?.firstOrNull()?.let { best ->
            highlights[best.from] = Palette.bestMoveSquare
            highlights[best.to] = Palette.bestMoveSquare
            if (settings.showArrows) {
                // Secondary lines first so the best move arrow is drawn on top.
                lines.drop(1).take(2).forEach { l ->
                    l.moves.firstOrNull()?.takeIf { it != best }?.let {
                        arrows.add(BoardArrow(it.from, it.to, Palette.arrowSecondary, 0.11f))
                    }
                }
                arrows.add(BoardArrow(best.from, best.to, Palette.arrow))
            }
        }
    }
    ChessBoard(
        position = position,
        flipped = settings.flipped,
        lastMove = preview?.lastMove ?: ui.game.lastMove,
        selected = ui.selected,
        targets = ui.targets,
        arrows = arrows,
        squareHighlights = highlights,
        showCoordinates = settings.showCoordinates,
        onTap = vm::onSquareTap,
        onDragStart = vm::onDragStart,
        onDrop = vm::onDrop,
        canDrag = vm::canDrag,
        modifier = modifier,
    )
}

@Composable
private fun EvalBar(analysis: AnalysisView?, ui: UiState, engine: EngineState) {
    val best = analysis?.best
    val outcome = ui.outcome
    val share = when {
        outcome?.result == "1-0" -> 1f
        outcome?.result == "0-1" -> 0f
        outcome != null -> 0.5f
        best != null -> AnalysisViewModel.whiteShare(best.whiteCp, best.whiteMate)
        else -> null
    }
    val animated by animateFloatAsState(share ?: 0.5f, label = "eval")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Palette.evalBlack),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(Palette.evalWhite),
        )
    }
}

// ---------------------------------------------------------------------------- info

@Composable
private fun ColumnScope.InfoColumn(ui: UiState, settings: AppSettings, engine: EngineState, analysis: AnalysisView?, vm: AnalysisViewModel) {
    EngineHeader(ui, settings, engine, analysis, vm)
    EngineLines(ui, analysis, settings, vm)
    HorizontalDivider(color = Palette.outline)
    MoveList(ui, vm, Modifier.weight(1f))
}

@Composable
private fun EngineHeader(ui: UiState, settings: AppSettings, engine: EngineState, analysis: AnalysisView?, vm: AnalysisViewModel) {
    val outcome = ui.outcome
    val best = analysis?.best
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Evaluation badge
        val evalText = when {
            outcome != null -> outcome.result.replace("1/2-1/2", "½-½")
            best != null -> best.evalText
            else -> "…"
        }
        val positive = best?.let { (it.whiteMate ?: 0) > 0 || (it.whiteMate == null && (it.whiteCp ?: 0) >= 0) } ?: true
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (positive) Palette.evalWhite else Palette.evalBlack)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .widthIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                evalText,
                color = if (positive) Color(0xFF15171A) else Palette.text,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            when {
                outcome != null -> {
                    Text(outcomeText(outcome.reason, outcome.result), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                engine.status == EngineStatus.ERROR -> {
                    Text(engine.error ?: "Ошибка движка", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 2)
                }
                engine.status == EngineStatus.STARTING || engine.status == EngineStatus.STOPPED -> {
                    Text("Запуск Stockfish…", color = Palette.textMuted, fontSize = 14.sp)
                }
                best != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Лучший ход ", color = Palette.textMuted, fontSize = 13.sp)
                        Text(best.sans.first(), color = Palette.best, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    Text(depthText(analysis, settings), color = Palette.textMuted, fontSize = 12.sp, maxLines = 1)
                }
                !settings.autoAnalysis && analysis == null -> {
                    Text("Автоанализ выключен", color = Palette.textMuted, fontSize = 14.sp)
                }
                else -> Text("Анализ…", color = Palette.textMuted, fontSize = 14.sp)
            }
        }
        if (best != null && outcome == null) {
            IconButton(onClick = { vm.showPreview(best) }) {
                Icon(Icons.Filled.Visibility, contentDescription = "Показать лучший ход на доске", tint = Palette.best)
            }
        }
        if (outcome == null) {
            val searching = engine.searching && engine.request?.key == ui.game.position.toFen()
            IconButton(onClick = { if (searching) vm.stopAnalysis() else vm.startAnalysis() }) {
                if (searching) {
                    Icon(Icons.Filled.Stop, contentDescription = "Остановить анализ")
                } else {
                    Icon(if (analysis != null) Icons.Filled.Refresh else Icons.Filled.PlayArrow, contentDescription = "Запустить анализ")
                }
            }
        }
    }
}

private fun depthText(a: AnalysisView?, s: AppSettings): String {
    if (a == null) return ""
    val target = if (s.isInfinite) "∞" else s.depth.toString()
    val knps = a.nps / 1000
    val nps = if (knps >= 1000) String.format("%.1f Mn/s", knps / 1000.0) else "$knps kn/s"
    val state = if (a.searching) "" else " · готово"
    return "Глубина ${a.depth}/$target · $nps$state"
}

private fun outcomeText(reason: OutcomeReason, result: String): String = when (reason) {
    OutcomeReason.CHECKMATE -> if (result == "1-0") "Мат. Победа белых" else "Мат. Победа чёрных"
    OutcomeReason.STALEMATE -> "Пат. Ничья"
    OutcomeReason.INSUFFICIENT_MATERIAL -> "Недостаточно материала. Ничья"
    OutcomeReason.THREEFOLD_REPETITION -> "Троекратное повторение"
    OutcomeReason.FIFTY_MOVES -> "Правило 50 ходов"
}

@Composable
private fun EngineLines(ui: UiState, analysis: AnalysisView?, settings: AppSettings, vm: AnalysisViewModel) {
    if (ui.outcome != null) return
    val lines = analysis?.lines.orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface),
    ) {
        if (lines.isEmpty()) {
            repeat(settings.multiPv.coerceAtMost(3)) {
                Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("—", color = Palette.textMuted, fontSize = 13.sp)
                }
            }
        }
        for (line in lines) {
            val previewing = ui.preview?.line?.index == line.index
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clickable { vm.showPreview(line) }
                    .background(if (previewing) Palette.surfaceHigh else Color.Transparent)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    line.evalText,
                    modifier = Modifier.width(58.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (line.index == 1) Palette.best else Palette.text,
                )
                Text(
                    line.text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Palette.text,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MoveList(ui: UiState, vm: AnalysisViewModel, modifier: Modifier) {
    val game = ui.game
    val scroll = rememberScrollState()
    val requesters = remember(game.size) { List(game.size + 1) { BringIntoViewRequester() } }
    LaunchedEffect(game.ply, game.size) {
        if (game.ply > 0) requesters.getOrNull(game.ply)?.bringIntoView()
        else scroll.animateScrollTo(0)
    }
    Box(modifier.fillMaxWidth()) {
        if (game.size == 0) {
            Text(
                "Сделайте ход на доске — за белых и за чёрных.\nStockfish проанализирует каждую позицию.",
                color = Palette.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
        FlowRow(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (i in 0 until game.size) {
                val white = game.isWhiteMove(i)
                if (white || i == 0) {
                    Text(
                        if (white) "${game.moveNumberOf(i)}." else "${game.moveNumberOf(i)}…",
                        color = Palette.textMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 5.dp),
                    )
                }
                val current = i + 1 == game.ply
                val future = i + 1 > game.ply
                Text(
                    game.sans[i],
                    color = when {
                        current -> Color(0xFF0C1520)
                        future -> Palette.textMuted
                        else -> Palette.text
                    },
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .bringIntoViewRequester(requesters[i + 1])
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (current) Palette.accent else Color.Transparent)
                        .clickable { vm.goTo(i + 1) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- bars

@Composable
private fun ControlBar(ui: UiState, settings: AppSettings, vm: AnalysisViewModel, actions: MenuActions) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = Palette.surface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.Filled.FirstPage, "В начало", ui.game.canBack) { vm.toStart() }
            BarButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Отменить ход", ui.game.canBack, big = true) { vm.back() }
            BarButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Вернуть ход", ui.game.canForward, big = true) { vm.forward() }
            BarButton(Icons.Filled.LastPage, "В конец", ui.game.canForward) { vm.toEnd() }
            BarButton(Icons.Filled.SwapVert, "Перевернуть доску", true) { vm.flipBoard() }
            BarButton(
                Icons.Filled.Bolt,
                if (settings.autoAnalysis) "Выключить автоанализ" else "Включить автоанализ",
                true,
                tint = if (settings.autoAnalysis) Palette.best else Palette.textMuted,
            ) { vm.toggleAutoAnalysis() }
            Box {
                BarButton(Icons.Filled.MoreVert, "Меню", true) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Новая партия", Icons.Filled.AddBox) { menu = false; actions.onNewGame() }
                    HorizontalDivider()
                    MenuItem("Открыть PGN-файл", Icons.Filled.FileOpen) { menu = false; actions.onOpenFile() }
                    MenuItem("Вставить PGN / FEN", Icons.Filled.ContentPaste) { menu = false; actions.onPaste() }
                    HorizontalDivider()
                    MenuItem("Поделиться PGN", Icons.Filled.Share) { menu = false; actions.onShare() }
                    MenuItem("Сохранить PGN в файл", Icons.Filled.Save) { menu = false; actions.onSave() }
                    MenuItem("Копировать PGN", Icons.Filled.ContentCopy) { menu = false; actions.onCopyPgn() }
                    MenuItem("Копировать FEN", Icons.Filled.ContentCopy) { menu = false; actions.onCopyFen() }
                    HorizontalDivider()
                    MenuItem("Настройки анализа", Icons.Filled.Settings) { menu = false; actions.onSettings() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    big: Boolean = false,
    tint: Color = Palette.text,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (big) 52.dp else 46.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else Palette.outline,
            modifier = Modifier.size(if (big) 34.dp else 26.dp),
        )
    }
}

@Composable
private fun PreviewBar(preview: PreviewState, vm: AnalysisViewModel) {
    Surface(color = Palette.surfaceHigh, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closePreview) { Icon(Icons.Filled.Close, contentDescription = "Закрыть просмотр") }
            IconButton(onClick = { vm.previewStep(-1) }, enabled = preview.step > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Назад по варианту", modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Вариант ${preview.line.index} · ${preview.line.evalText}", fontSize = 12.sp, color = Palette.textMuted)
                val shown = preview.line.sans.take(preview.step).lastOrNull() ?: "исходная позиция"
                Text(
                    "${preview.step}/${preview.line.moves.size}: $shown",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.previewArrow,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { vm.previewStep(1) }, enabled = preview.step < preview.line.moves.size) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Вперёд по варианту", modifier = Modifier.size(32.dp))
            }
            TextButton(onClick = vm::applyPreview, enabled = preview.step > 0) { Text("Сыграть") }
        }
    }
}

// ---------------------------------------------------------------------------- dialogs

@Composable
private fun PromotionDialog(side: Side, onPick: (PieceType?) -> Unit) {
    val images = rememberPieceImages()
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text("Превращение пешки") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (t in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.lightSquare)
                            .clickable { onPick(t) },
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = images.getValue(t to side),
                            contentDescription = t.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("Отмена") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit, onOpenFile: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт PGN или FEN") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    placeholder = { Text("[Event \"...\"]\n1. e4 e5 2. Nf3 ...\n\nили FEN позиции") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    }) {
                        Icon(Icons.Filled.ContentPaste, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Из буфера")
                    }
                    TextButton(onClick = onOpenFile) {
                        Icon(Icons.Filled.FileOpen, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Файл")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Загрузить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun sharePgn(context: Context, pgn: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, pgn)
        putExtra(Intent.EXTRA_SUBJECT, "Партия (PGN)")
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться PGN"))
}

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
