package com.stockfishmobile.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockfishmobile.chess.Game
import com.stockfishmobile.chess.IllegalFenException
import com.stockfishmobile.chess.Move
import com.stockfishmobile.chess.Outcome
import com.stockfishmobile.chess.Pgn
import com.stockfishmobile.chess.PgnGame
import com.stockfishmobile.chess.PieceType
import com.stockfishmobile.chess.Position
import com.stockfishmobile.chess.San
import com.stockfishmobile.chess.Side
import com.stockfishmobile.data.AppSettings
import com.stockfishmobile.data.SettingsRepository
import com.stockfishmobile.engine.AnalysisRequest
import com.stockfishmobile.engine.EngineOptions
import com.stockfishmobile.engine.EngineState
import com.stockfishmobile.engine.EngineStatus
import com.stockfishmobile.engine.PvLine
import com.stockfishmobile.engine.StockfishInstaller
import com.stockfishmobile.engine.UciEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/** One engine line prepared for display (scores from White's point of view). */
data class LineView(
    val index: Int,
    val evalText: String,
    val whiteCp: Int?,
    val whiteMate: Int?,
    val depth: Int,
    val moves: List<Move>,
    val sans: List<String>,
    /** PV formatted with move numbers, e.g. "12. Nf3 Nc6 13. Bb5". */
    val text: String,
)

data class AnalysisView(
    val lines: List<LineView>,
    val depth: Int,
    val selDepth: Int,
    val nodes: Long,
    val nps: Long,
    val searching: Boolean,
) {
    val best: LineView? get() = lines.firstOrNull()
}

/** Showing an engine line on the board without changing the game. */
data class PreviewState(
    val line: LineView,
    /** Number of PV moves applied (1..line.moves.size). */
    val step: Int,
    val positions: List<Position>,
) {
    val position: Position get() = positions[step]
    val lastMove: Move? get() = if (step > 0) line.moves[step - 1] else null
    val nextMove: Move? get() = line.moves.getOrNull(step)
}

data class PromotionRequest(val from: Int, val to: Int, val side: Side)

data class UiState(
    val game: Game = Game.new(),
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    val promotion: PromotionRequest? = null,
    val preview: PreviewState? = null,
    val pgnChoices: List<PgnGame>? = null,
    val engineVariant: String? = null,
    val message: String? = null,
) {
    val outcome: Outcome? get() = game.outcome()
}

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    private val _settings = MutableStateFlow(repo.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(UiState(game = restoreGame()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val engineHolder = MutableStateFlow<UciEngine?>(null)
    private val installError = MutableStateFlow<String?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val engineState: StateFlow<EngineState> = engineHolder
        .flatMapLatest { it?.state ?: flowOf(EngineState(status = EngineStatus.STARTING)) }
        .combine(installError) { st, err -> if (err != null) EngineState(status = EngineStatus.ERROR, error = err) else st }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineState(status = EngineStatus.STARTING))

    /** Engine output for the position currently on the board (null if stale / none). */
    val analysis: StateFlow<AnalysisView?> = combine(_ui, engineState) { ui, engine ->
        buildAnalysis(ui.game, engine)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var inForeground = true

    init {
        viewModelScope.launch {
            try {
                val inst = withContext(Dispatchers.IO) {
                    (installationOverride ?: StockfishInstaller::install)(getApplication())
                }
                val s = _settings.value
                val engine = UciEngine(
                    command = listOf(inst.binary.absolutePath),
                    workingDir = inst.network.parentFile,
                    initOptions = mapOf("EvalFile" to inst.network.absolutePath),
                    options = EngineOptions(s.threads, s.hashMb),
                    log = { Log.d("UCI", it) },
                )
                withContext(Dispatchers.IO) { engine.start() }
                engineHolder.value = engine
                _ui.update { it.copy(engineVariant = inst.variant) }
                requestAnalysis()
            } catch (e: Exception) {
                Log.e("Engine", "install failed", e)
                installError.value = "Не удалось подготовить Stockfish: ${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------ board input

    fun onSquareTap(sq: Int) {
        val st = _ui.value
        if (st.preview != null) {
            closePreview()
            return
        }
        val pos = st.game.position
        val sel = st.selected
        if (sel != null && sq in st.targets) {
            tryMove(sel, sq)
            return
        }
        val piece = pos[sq]
        if (piece != null && piece.side == pos.sideToMove && sel != sq) {
            val targets = pos.legalMovesFrom(sq).map { it.to }.toSet()
            _ui.update { it.copy(selected = sq, targets = targets) }
        } else {
            clearSelection()
        }
    }

    /** Drag & drop: [from] to [to]. */
    fun onDrop(from: Int, to: Int) {
        val pos = _ui.value.game.position
        if (_ui.value.preview != null) return
        if (pos.legalMovesFrom(from).any { it.to == to }) tryMove(from, to) else {
            // Dropping back on the origin keeps the selection for tap-to-move.
            if (from == to) {
                val targets = pos.legalMovesFrom(from).map { it.to }.toSet()
                _ui.update { it.copy(selected = from, targets = targets) }
            } else clearSelection()
        }
    }

    /** Selects the dragged piece so its legal targets are shown while dragging. */
    fun onDragStart(sq: Int) {
        if (!canDrag(sq)) return
        val targets = _ui.value.game.position.legalMovesFrom(sq).map { it.to }.toSet()
        _ui.update { it.copy(selected = sq, targets = targets) }
    }

    fun canDrag(sq: Int): Boolean {
        val pos = _ui.value.game.position
        return _ui.value.preview == null && pos[sq]?.side == pos.sideToMove
    }

    private fun tryMove(from: Int, to: Int) {
        val pos = _ui.value.game.position
        if (pos.isPromotionMove(from, to)) {
            _ui.update { it.copy(promotion = PromotionRequest(from, to, pos.sideToMove)) }
            return
        }
        playMove(Move(from, to))
    }

    fun onPromotionChosen(type: PieceType?) {
        val req = _ui.value.promotion ?: return
        _ui.update { it.copy(promotion = null) }
        if (type == null) {
            clearSelection()
            return
        }
        playMove(Move(req.from, req.to, type))
    }

    private fun clearSelection() = _ui.update { it.copy(selected = null, targets = emptySet()) }

    private fun playMove(move: Move) {
        val g = _ui.value.game
        if (!g.position.isLegal(move)) return
        setGame(g.play(move))
    }

    // ------------------------------------------------------------------ navigation

    fun back() = _ui.value.game.let { if (it.canBack) setGame(it.back()) }
    fun forward() = _ui.value.game.let { if (it.canForward) setGame(it.forward()) }
    fun toStart() = setGame(_ui.value.game.toStart())
    fun toEnd() = setGame(_ui.value.game.toEnd())
    fun goTo(ply: Int) = setGame(_ui.value.game.goTo(ply))

    fun newGame() {
        engineHolder.value?.stop()
        engineHolder.value?.newGame()
        setGame(Game.new())
    }

    private fun setGame(game: Game) {
        _ui.update { it.copy(game = game, selected = null, targets = emptySet(), preview = null, promotion = null) }
        repo.saveGame(Pgn.export(game), game.ply)
        requestAnalysis()
    }

    // ------------------------------------------------------------------ engine control

    fun toggleAutoAnalysis() {
        updateSettings { it.copy(autoAnalysis = !it.autoAnalysis) }
        if (!_settings.value.autoAnalysis) engineHolder.value?.stop()
    }

    /** Starts analysis of the current position (also when auto-analysis is off). */
    fun startAnalysis() = requestAnalysis(force = true)

    fun stopAnalysis() {
        engineHolder.value?.stop()
    }

    private fun requestAnalysis(force: Boolean = false) {
        val engine = engineHolder.value ?: return
        val s = _settings.value
        val g = _ui.value.game
        if ((!s.autoAnalysis && !force) || !inForeground) {
            engine.stop()
            return
        }
        if (g.position.legalMoves.isEmpty()) {
            engine.stop()
            return
        }
        engine.setOptions(EngineOptions(s.threads, s.hashMb))
        engine.analyze(
            AnalysisRequest(
                startFen = g.startPosition.toFen(),
                moves = g.uciMovesToCurrent(),
                depth = if (s.isInfinite) null else s.depth,
                multiPv = s.multiPv,
                key = g.position.toFen(),
            ),
        )
    }

    fun onForeground() {
        inForeground = true
        val engine = engineHolder.value
        if (engine != null && !engine.isAlive) engine.start()
        requestAnalysis()
    }

    fun onBackground() {
        inForeground = false
        engineHolder.value?.stop()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        repo.save(new)
        val engineRelevant = old.depth != new.depth || old.multiPv != new.multiPv ||
            old.threads != new.threads || old.hashMb != new.hashMb ||
            (!old.autoAnalysis && new.autoAnalysis)
        if (engineRelevant) requestAnalysis()
    }

    fun flipBoard() = updateSettings { it.copy(flipped = !it.flipped) }

    // ------------------------------------------------------------------ preview of engine lines

    fun showPreview(line: LineView, step: Int = 1) {
        if (line.moves.isEmpty()) return
        val positions = ArrayList<Position>(line.moves.size + 1)
        var p = _ui.value.game.position
        positions.add(p)
        for (m in line.moves) {
            if (!p.isLegal(m)) break
            p = p.play(m)
            positions.add(p)
        }
        val usable = line.copy(moves = line.moves.take(positions.size - 1))
        _ui.update {
            it.copy(
                preview = PreviewState(usable, step.coerceIn(1, positions.size - 1), positions),
                selected = null,
                targets = emptySet(),
            )
        }
    }

    fun previewStep(delta: Int) {
        val p = _ui.value.preview ?: return
        val step = (p.step + delta).coerceIn(0, p.line.moves.size)
        _ui.update { it.copy(preview = p.copy(step = step)) }
    }

    fun closePreview() = _ui.update { it.copy(preview = null) }

    /** Plays the previewed line up to the current preview step into the game. */
    fun applyPreview() {
        val p = _ui.value.preview ?: return
        var g = _ui.value.game
        for (m in p.line.moves.take(p.step)) {
            if (!g.position.isLegal(m)) break
            g = g.play(m)
        }
        setGame(g)
    }

    // ------------------------------------------------------------------ PGN / FEN

    fun importText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            showMessage("Пустой текст")
            return
        }
        // A bare FEN?
        if (looksLikeFen(trimmed)) {
            try {
                val pos = Position.fromFen(trimmed)
                engineHolder.value?.newGame()
                setGame(Game.new(pos, mapOf("SetUp" to "1", "FEN" to pos.toFen())))
                showMessage("Позиция загружена")
            } catch (e: IllegalFenException) {
                showMessage("Некорректный FEN: ${e.message}")
            }
            return
        }
        val games = try {
            Pgn.parse(trimmed)
        } catch (e: Exception) {
            showMessage("Ошибка PGN: ${e.message}")
            return
        }
        when {
            games.isEmpty() -> showMessage("В файле не найдено партий")
            games.size == 1 -> loadPgnGame(games[0])
            else -> _ui.update { it.copy(pgnChoices = games) }
        }
    }

    fun loadPgnGame(pg: PgnGame) {
        _ui.update { it.copy(pgnChoices = null) }
        if (pg.moves.isEmpty() && pg.error != null) {
            showMessage(pg.error)
            return
        }
        engineHolder.value?.newGame()
        setGame(pg.toGame())
        showMessage(pg.error?.let { "Загружено ${pg.moves.size} полуходов. $it" } ?: "Партия загружена: ${pg.moves.size} полуходов")
    }

    fun dismissPgnChoices() = _ui.update { it.copy(pgnChoices = null) }

    fun exportPgn(): String = Pgn.export(_ui.value.game)

    fun currentFen(): String = _ui.value.game.position.toFen()

    fun showMessage(msg: String) = _ui.update { it.copy(message = msg) }
    fun messageShown() = _ui.update { it.copy(message = null) }

    private fun looksLikeFen(s: String): Boolean =
        !s.contains('\n') && s.count { it == '/' } == 7 && !s.startsWith("[") && !s.contains('.')

    private fun restoreGame(): Game {
        val saved = repo.loadGame() ?: return Game.new()
        return try {
            val pg = Pgn.parse(saved.first).firstOrNull() ?: return Game.new()
            val g = Game.fromMoves(pg.start, pg.moves, pg.tags)
            g.goTo(saved.second)
        } catch (e: Exception) {
            Game.new()
        }
    }

    override fun onCleared() {
        engineHolder.value?.let { e -> Thread { e.quit() }.start() }
        super.onCleared()
    }

    // ------------------------------------------------------------------ formatting

    private fun buildAnalysis(game: Game, engine: EngineState): AnalysisView? {
        val req = engine.request ?: return null
        val pos = game.position
        if (req.key != pos.toFen()) return null
        val sign = if (pos.sideToMove == Side.WHITE) 1 else -1
        val lines = engine.lines.mapNotNull { toLineView(pos, game, it, sign) }
        val top = engine.lines.firstOrNull()
        return AnalysisView(
            lines = lines,
            depth = top?.depth ?: 0,
            selDepth = top?.selDepth ?: 0,
            nodes = engine.nodes,
            nps = engine.nps,
            searching = engine.searching,
        )
    }

    private fun toLineView(pos: Position, game: Game, pv: PvLine, sign: Int): LineView? {
        val moves = ArrayList<Move>()
        val sans = ArrayList<String>()
        var p = pos
        for (u in pv.pv) {
            val m = Move.fromUci(u) ?: break
            if (!p.isLegal(m)) break
            sans.add(San.toSan(p, m))
            moves.add(m)
            p = p.play(m)
            if (moves.size >= MAX_PV_MOVES) break
        }
        if (moves.isEmpty()) return null
        val whiteCp = pv.scoreCp?.let { it * sign }
        val whiteMate = pv.mate?.let { it * sign }
        return LineView(
            index = pv.multiPv,
            evalText = formatEval(whiteCp, whiteMate),
            whiteCp = whiteCp,
            whiteMate = whiteMate,
            depth = pv.depth,
            moves = moves,
            sans = sans,
            text = formatPv(pos, sans),
        )
    }

    companion object {
        private const val MAX_PV_MOVES = 16

        /** Tests can point the app at a host-built engine binary. */
        @androidx.annotation.VisibleForTesting
        var installationOverride: ((android.content.Context) -> StockfishInstaller.Installation)? = null

        fun formatEval(cp: Int?, mate: Int?): String = when {
            mate != null && mate > 0 -> "M$mate"
            mate != null && mate < 0 -> "-M${abs(mate)}"
            mate != null -> "M0"
            cp != null -> String.format(Locale.US, "%+.2f", cp / 100.0).let { if (cp == 0) "0.00" else it }
            else -> "…"
        }

        /** White's expected score in [0, 1] for the evaluation bar. */
        fun whiteShare(cp: Int?, mate: Int?): Float = when {
            mate != null -> if (mate > 0) 1f else if (mate < 0) 0f else 0.5f
            cp != null -> (1.0 / (1.0 + exp(-0.00368208 * cp))).toFloat()
            else -> 0.5f
        }

        fun formatPv(start: Position, sans: List<String>): String = buildString {
            var number = start.fullmoveNumber
            var white = start.sideToMove == Side.WHITE
            for ((i, s) in sans.withIndex()) {
                if (white) append(number).append(". ")
                else if (i == 0) append(number).append("... ")
                append(s).append(' ')
                if (!white) number++
                white = !white
            }
        }.trimEnd()
    }
}
