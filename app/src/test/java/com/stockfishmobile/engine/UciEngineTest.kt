package com.stockfishmobile.engine

import com.stockfishmobile.chess.Move
import com.stockfishmobile.chess.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Integration test against a real Stockfish binary built for the host.
 * Set SF_HOST_BINARY and SF_NNUE environment variables to run it, e.g.
 *   (cd third_party/stockfish-src/src && make build ARCH=x86-64-sse41-popcnt EXTRACXXFLAGS=-DNNUE_EMBEDDING_OFF)
 */
class UciEngineTest {
    private val binary = System.getenv("SF_HOST_BINARY")?.let(::File)
    private val net = System.getenv("SF_NNUE")?.let(::File)

    private fun engine(): UciEngine {
        assumeTrue("SF_HOST_BINARY not set", binary?.canExecute() == true && net?.exists() == true)
        return UciEngine(
            command = listOf(binary!!.absolutePath),
            initOptions = mapOf("EvalFile" to net!!.absolutePath),
            options = EngineOptions(threads = 2, hashMb = 32),
            log = { println(it) },
        )
    }

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timeout")
            Thread.sleep(20)
        }
    }

    @Test fun findsMateInOne() {
        val e = engine()
        e.start()
        // Scholar's mate pattern: Qxf7#
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 1"
        e.analyze(AnalysisRequest(fen, emptyList(), depth = 10, multiPv = 3, key = fen))
        waitFor { e.state.value.status == EngineStatus.READY && !e.state.value.searching && e.state.value.bestMove != null }
        val st = e.state.value
        assertEquals("h5f7", st.bestMove)
        assertEquals(1, st.lines.first().mate)
        assertTrue(st.lines.size >= 2)
        assertTrue(st.engineName!!.startsWith("Stockfish"))
        e.quit()
    }

    @Test fun switchingPositionsDiscardsStaleOutput() {
        val e = engine()
        e.start()
        val start = Position.START_FEN
        e.analyze(AnalysisRequest(start, emptyList(), depth = null, multiPv = 1, key = "a"))
        Thread.sleep(300)
        // Rapidly change positions, like a user stepping through a game.
        val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5")
        for (i in 1..moves.size) {
            e.analyze(AnalysisRequest(start, moves.take(i), depth = 12, multiPv = 2, key = "k$i"))
            Thread.sleep(30)
        }
        waitFor { !e.state.value.searching && e.state.value.bestMove != null && e.state.value.request?.key == "k5" }
        val st = e.state.value
        // After 1.e4 e5 2.Nf3 Nc6 3.Bb5 it is Black to move: all PV moves must be legal there.
        var pos = Position.START
        for (m in moves) pos = pos.play(Move.fromUci(m)!!)
        for (line in st.lines) {
            val first = Move.fromUci(line.pv.first())
            assertNotNull(first)
            assertTrue("PV ${line.pv} not legal in position", pos.isLegal(first!!))
        }
        assertEquals(12, st.lines.first().depth)
        e.quit()
    }

    @Test fun stopKeepsLines() {
        val e = engine()
        e.start()
        e.analyze(AnalysisRequest(Position.START_FEN, emptyList(), depth = null, multiPv = 1, key = "x"))
        waitFor { e.state.value.lines.isNotEmpty() && e.state.value.depth >= 8 }
        e.stop()
        waitFor { !e.state.value.searching }
        assertTrue(e.state.value.lines.isNotEmpty())
        e.quit()
    }

    @Test fun parseInfoLine() {
        val l = UciEngine.parseInfo(
            "info depth 20 seldepth 28 multipv 2 score cp -35 upperbound nodes 123456 nps 999 hashfull 10 tbhits 0 time 1234 pv e7e5 g1f3",
        )!!
        assertEquals(2, l.multiPv)
        assertEquals(-35, l.scoreCp)
        assertEquals("upperbound", l.bound)
        assertEquals(listOf("e7e5", "g1f3"), l.pv)
        val m = UciEngine.parseInfo("info depth 5 multipv 1 score mate -3 wdl 0 0 1000 nodes 10 pv a1a2")!!
        assertEquals(-3, m.mate)
    }
}
