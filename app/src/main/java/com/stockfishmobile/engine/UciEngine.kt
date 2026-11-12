package com.stockfishmobile.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One principal variation reported by the engine. Score is from the side to move. */
data class PvLine(
    val multiPv: Int,
    val depth: Int,
    val selDepth: Int,
    val scoreCp: Int?,
    val mate: Int?,
    /** "lowerbound" / "upperbound" or null for an exact score. */
    val bound: String?,
    val pv: List<String>,
    val nodes: Long,
    val nps: Long,
    val timeMs: Long,
)

/** What to analyse. [depth] null means infinite analysis (until stopped). */
data class AnalysisRequest(
    val startFen: String,
    val moves: List<String>,
    val depth: Int?,
    val multiPv: Int,
    /** Opaque key identifying the analysed position (e.g. its FEN). */
    val key: String,
)

data class EngineOptions(val threads: Int, val hashMb: Int)

enum class EngineStatus { STOPPED, STARTING, READY, ERROR }

data class EngineState(
    val status: EngineStatus = EngineStatus.STOPPED,
    val engineName: String? = null,
    val request: AnalysisRequest? = null,
    val searching: Boolean = false,
    val lines: List<PvLine> = emptyList(),
    val bestMove: String? = null,
    val error: String? = null,
) {
    val depth: Int get() = lines.firstOrNull()?.depth ?: 0
    val nodes: Long get() = lines.maxOfOrNull { it.nodes } ?: 0
    val nps: Long get() = lines.maxOfOrNull { it.nps } ?: 0
}

/**
 * A UCI client for an external engine process (Stockfish).
 *
 * Search requests are serialised: when a new position arrives during a search
 * the client sends `stop`, discards output of the old search until its
 * `bestmove`, and only then starts the new one. This guarantees that every
 * `info` line shown belongs to the position on screen.
 */
class UciEngine(
    private val command: List<String>,
    private val workingDir: File? = null,
    /** Options sent once after `uciok` (e.g. EvalFile). */
    private val initOptions: Map<String, String> = emptyMap(),
    private var options: EngineOptions,
    private val log: (String) -> Unit = {},
) {
    private val lock = Object()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null

    private var ready = false
    private var searching = false
    private var stopSent = false
    private var pending: AnalysisRequest? = null
    private var current: AnalysisRequest? = null
    private var optionsDirty = true
    private var sentMultiPv = -1
    private val lines = sortedMapOf<Int, PvLine>()
    private var lastEmit = 0L

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val isAlive: Boolean get() = process?.isAlive == true

    /** Starts the engine process (no-op if already running). */
    fun start() {
        synchronized(lock) {
            if (isAlive) return
            ready = false
            searching = false
            stopSent = false
            current = null
            sentMultiPv = -1
            optionsDirty = true
            lines.clear()
            _state.value = EngineState(status = EngineStatus.STARTING)
            val p = try {
                ProcessBuilder(command).apply {
                    workingDir?.let { directory(it) }
                    redirectErrorStream(true)
                }.start()
            } catch (e: IOException) {
                _state.value = EngineState(status = EngineStatus.ERROR, error = "Не удалось запустить движок: ${e.message}")
                return
            }
            process = p
            writer = p.outputStream.bufferedWriter()
            readerThread = Thread({ readLoop(p) }, "uci-reader").apply { isDaemon = true; start() }
            send("uci")
        }
    }

    fun analyze(request: AnalysisRequest) {
        synchronized(lock) {
            if (!isAlive) start()
            pending = request
            if (!ready) {
                _state.value = _state.value.copy(request = request, lines = emptyList(), bestMove = null, searching = true)
                return
            }
            if (searching) {
                if (!stopSent) {
                    send("stop")
                    stopSent = true
                }
                // Show that we are now working on the new position.
                _state.value = _state.value.copy(request = request, lines = emptyList(), bestMove = null, searching = true)
            } else {
                startPending()
            }
        }
    }

    /** Stops the current search; keeps the last reported lines. */
    fun stop() {
        synchronized(lock) {
            pending = null
            if (searching && !stopSent) {
                send("stop")
                stopSent = true
            }
            if (!searching || !ready) {
                _state.value = _state.value.copy(searching = false)
            }
        }
    }

    fun setOptions(newOptions: EngineOptions) {
        synchronized(lock) {
            if (newOptions != options) {
                options = newOptions
                optionsDirty = true
            }
        }
    }

    /** Clears the transposition table before the next search (new game). */
    fun newGame() {
        synchronized(lock) {
            if (ready && !searching) send("ucinewgame")
        }
    }

    fun quit() {
        val p: Process
        synchronized(lock) {
            p = process ?: return
            pending = null
            try {
                if (searching) send("stop")
                send("quit")
            } catch (_: Exception) {
            }
            process = null
            ready = false
            searching = false
            _state.value = EngineState(status = EngineStatus.STOPPED)
        }
        try {
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroy()
        } catch (_: InterruptedException) {
            p.destroy()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun send(cmd: String) {
        log("> $cmd")
        try {
            writer?.apply {
                write(cmd)
                write("\n")
                flush()
            }
        } catch (e: IOException) {
            log("write failed: ${e.message}")
        }
    }

    /** Must hold [lock]. Requires the engine to be idle. */
    private fun startPending() {
        val req = pending ?: return
        pending = null
        if (optionsDirty) {
            send("setoption name Threads value ${options.threads}")
            send("setoption name Hash value ${options.hashMb}")
            optionsDirty = false
        }
        if (req.multiPv != sentMultiPv) {
            send("setoption name MultiPV value ${req.multiPv}")
            sentMultiPv = req.multiPv
        }
        val pos = buildString {
            append("position fen ").append(req.startFen)
            if (req.moves.isNotEmpty()) append(" moves ").append(req.moves.joinToString(" "))
        }
        send(pos)
        send(if (req.depth == null) "go infinite" else "go depth ${req.depth}")
        current = req
        searching = true
        stopSent = false
        lines.clear()
        _state.value = _state.value.copy(request = req, searching = true, lines = emptyList(), bestMove = null, error = null)
    }

    private fun readLoop(p: Process) {
        try {
            p.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) handleLine(line)
            }
        } catch (e: IOException) {
            log("read failed: ${e.message}")
        }
        synchronized(lock) {
            if (process === p) {
                process = null
                ready = false
                searching = false
                val code = try { p.exitValue() } catch (_: Exception) { -1 }
                _state.value = _state.value.copy(
                    status = EngineStatus.ERROR,
                    searching = false,
                    error = "Движок неожиданно завершился (код $code). Он будет перезапущен при следующем анализе.",
                )
            }
        }
    }

    private fun handleLine(line: String) {
        if (!line.startsWith("info") || line.contains(" string ")) log("< $line")
        synchronized(lock) {
            when {
                line.startsWith("info ") -> handleInfo(line)
                line.startsWith("bestmove") -> handleBestMove(line)
                line.startsWith("id name ") -> _state.value = _state.value.copy(engineName = line.removePrefix("id name ").trim())
                line == "uciok" -> {
                    for ((k, v) in initOptions) send("setoption name $k value $v")
                    send("setoption name Threads value ${options.threads}")
                    send("setoption name Hash value ${options.hashMb}")
                    optionsDirty = false
                    send("isready")
                }
                line == "readyok" -> {
                    if (!ready) {
                        ready = true
                        _state.value = _state.value.copy(status = EngineStatus.READY, error = null)
                        if (pending != null) startPending() else _state.value = _state.value.copy(searching = false)
                    }
                }
            }
        }
    }

    private fun handleBestMove(line: String) {
        searching = false
        val wasStopped = stopSent
        stopSent = false
        if (pending != null) {
            startPending()
            return
        }
        val best = line.split(' ').getOrNull(1)?.takeIf { it != "(none)" }
        _state.value = _state.value.copy(
            searching = false,
            lines = if (wasStopped && lines.isEmpty()) _state.value.lines else lines.values.toList(),
            bestMove = best,
        )
    }

    private fun handleInfo(line: String) {
        if (line.startsWith("info string")) {
            if (line.contains("ERROR", ignoreCase = false)) {
                _state.value = _state.value.copy(error = line.removePrefix("info string ").trim())
            }
            return
        }
        if (!searching || stopSent) return // stale output from a search being stopped
        val pv = parseInfo(line) ?: return
        val prevTop = lines[1]?.depth ?: 0
        lines[pv.multiPv] = pv
        // Drop lines with a higher index than MultiPV (e.g. fewer legal moves).
        val now = System.currentTimeMillis()
        if (pv.multiPv == 1 && pv.depth != prevTop || now - lastEmit > 150) {
            lastEmit = now
            _state.value = _state.value.copy(lines = lines.values.toList())
        }
    }

    companion object {
        /** Parses an `info ... pv ...` line; returns null for lines without a PV/score. */
        fun parseInfo(line: String): PvLine? {
            val t = line.split(' ').filter { it.isNotEmpty() }
            var depth = 0
            var sel = 0
            var multiPv = 1
            var cp: Int? = null
            var mate: Int? = null
            var bound: String? = null
            var nodes = 0L
            var nps = 0L
            var time = 0L
            var pv: List<String> = emptyList()
            var i = 1
            while (i < t.size) {
                when (t[i]) {
                    "depth" -> depth = t.getOrNull(++i)?.toIntOrNull() ?: 0
                    "seldepth" -> sel = t.getOrNull(++i)?.toIntOrNull() ?: 0
                    "multipv" -> multiPv = t.getOrNull(++i)?.toIntOrNull() ?: 1
                    "nodes" -> nodes = t.getOrNull(++i)?.toLongOrNull() ?: 0
                    "nps" -> nps = t.getOrNull(++i)?.toLongOrNull() ?: 0
                    "time" -> time = t.getOrNull(++i)?.toLongOrNull() ?: 0
                    "score" -> {
                        when (t.getOrNull(++i)) {
                            "cp" -> cp = t.getOrNull(++i)?.toIntOrNull()
                            "mate" -> mate = t.getOrNull(++i)?.toIntOrNull()
                        }
                        val b = t.getOrNull(i + 1)
                        if (b == "lowerbound" || b == "upperbound") {
                            bound = b
                            i++
                        }
                    }
                    "pv" -> {
                        pv = t.subList(i + 1, t.size)
                        i = t.size
                    }
                    "currmove", "currmovenumber", "hashfull", "tbhits", "cpuload", "wdl" -> {
                        if (t[i] == "wdl") i += 3 else i++
                    }
                }
                i++
            }
            if (pv.isEmpty() || (cp == null && mate == null)) return null
            return PvLine(multiPv, depth, sel, cp, mate, bound, pv, nodes, nps, time)
        }
    }
}
