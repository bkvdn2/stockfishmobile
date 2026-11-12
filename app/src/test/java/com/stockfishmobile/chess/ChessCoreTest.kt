package com.stockfishmobile.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessCoreTest {

    private fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = pos.legalMoves
        if (depth == 1) return moves.size.toLong()
        var n = 0L
        for (m in moves) n += perft(pos.play(m), depth - 1)
        return n
    }

    // Reference values from https://www.chessprogramming.org/Perft_Results
    @Test fun perftStart() {
        val p = Position.START
        assertEquals(20, perft(p, 1))
        assertEquals(400, perft(p, 2))
        assertEquals(8902, perft(p, 3))
        assertEquals(197281, perft(p, 4))
    }

    @Test fun perftKiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(p, 1))
        assertEquals(2039, perft(p, 2))
        assertEquals(97862, perft(p, 3))
        assertEquals(4085603, perft(p, 4))
    }

    @Test fun perftPosition3() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, perft(p, 1))
        assertEquals(191, perft(p, 2))
        assertEquals(2812, perft(p, 3))
        assertEquals(43238, perft(p, 4))
        assertEquals(674624, perft(p, 5))
    }

    @Test fun perftPosition4() {
        val p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, perft(p, 1))
        assertEquals(264, perft(p, 2))
        assertEquals(9467, perft(p, 3))
        assertEquals(422333, perft(p, 4))
    }

    @Test fun perftPosition5() {
        val p = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44, perft(p, 1))
        assertEquals(1486, perft(p, 2))
        assertEquals(62379, perft(p, 3))
    }

    @Test fun perftPosition6() {
        val p = Position.fromFen("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10")
        assertEquals(46, perft(p, 1))
        assertEquals(2079, perft(p, 2))
        assertEquals(89890, perft(p, 3))
    }

    @Test fun fenRoundTrip() {
        val fens = listOf(
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
        )
        for (f in fens) assertEquals(f, Position.fromFen(f).toFen())
    }

    @Test fun epSquareOnlyWhenCapturable() {
        val p = Position.START.play(Move.fromUci("e2e4")!!)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", p.toFen())
    }

    @Test fun sanGeneration() {
        var p = Position.START
        val ucis = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6", "e1g1")
        val expected = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Bxc6", "dxc6", "O-O")
        for ((u, s) in ucis.zip(expected)) {
            val m = Move.fromUci(u)!!
            assertEquals(s, San.toSan(p, m))
            p = p.play(m)
        }
    }

    @Test fun sanDisambiguationAndPromotion() {
        val p = Position.fromFen("1k6/4P3/8/8/8/8/1K6/R6R w - - 0 1")
        assertEquals("Rab1", San.toSan(p, Move.fromUci("a1b1")!!))
        assertEquals("Rhb1", San.toSan(p, Move.fromUci("h1b1")!!))
        assertEquals("e8=Q+", San.toSan(p, Move(Sq.parse("e7"), Sq.parse("e8"), PieceType.QUEEN)))
        assertEquals("e8=N", San.toSan(p, Move(Sq.parse("e7"), Sq.parse("e8"), PieceType.KNIGHT)))
        val q = Position.fromFen("7k/8/8/8/R7/8/8/R6K w - - 0 1")
        assertEquals("R1a2", San.toSan(q, Move.fromUci("a1a2")!!))
        assertEquals("R4a2", San.toSan(q, Move.fromUci("a4a2")!!))
    }

    @Test fun checkmateSuffix() {
        var p = Position.START
        for (u in listOf("f2f3", "e7e5", "g2g4")) p = p.play(Move.fromUci(u)!!)
        val m = Move.fromUci("d8h4")!!
        assertEquals("Qh4#", San.toSan(p, m))
        assertTrue(p.play(m).isCheckmate)
    }

    @Test fun sanParsingLenient() {
        val p = Position.START
        assertEquals(Move.fromUci("g1f3"), San.parse(p, "Nf3"))
        assertEquals(Move.fromUci("g1f3"), San.parse(p, "Ngf3"))
        assertEquals(Move.fromUci("g1f3"), San.parse(p, "Ng1-f3"))
        assertEquals(Move.fromUci("e2e4"), San.parse(p, "e4!?"))
        assertEquals(Move.fromUci("e2e4"), San.parse(p, "e2e4"))
        assertNull(San.parse(p, "e5"))
        assertNull(San.parse(p, "Nd2"))
        val promo = Position.fromFen("1k6/4P3/8/8/8/8/1K6/8 w - - 0 1")
        assertEquals(Move(Sq.parse("e7"), Sq.parse("e8"), PieceType.QUEEN), San.parse(promo, "e8Q"))
        assertEquals(Move(Sq.parse("e7"), Sq.parse("e8"), PieceType.ROOK), San.parse(promo, "e8=R+"))
        val castle = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1")
        assertEquals(Move.fromUci("e8c8"), San.parse(castle, "0-0-0"))
        assertEquals(Move.fromUci("e8g8"), San.parse(castle, "O-O"))
    }

    @Test fun castlingThroughCheckIsIllegal() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/5r2/R3K2R w KQkq - 0 1")
        assertFalse(p.isLegal(Move.fromUci("e1g1")!!))
        assertTrue(p.isLegal(Move.fromUci("e1c1")!!)) // f2 rook does not attack e1, d1 or c1
    }

    @Test fun enPassantCapture() {
        val p = Position.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3")
        val m = Move.fromUci("e5f6")!!
        assertTrue(p.isLegal(m))
        assertEquals("exf6", San.toSan(p, m))
        val after = p.play(m)
        assertNull(after[Sq.parse("f5")])
    }

    @Test fun pgnRoundTrip() {
        val pgn = """
            [Event "Test"]
            [White "Morphy, Paul"]
            [Black "Duke Karl / Count Isouard"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 d6 3. d4 Bg4 {This is a weak move} 4. dxe5 Bxf3 5. Qxf3 dxe5
            6. Bc4 Nf6 7. Qb3 Qe7 8. Nc3 (8. Qxb7 Qb4+) 8... c6 9. Bg5 $2 b5 10. Nxb5 cxb5
            11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7
            16. Qb8+ Nxb8 17. Rd8# 1-0
        """.trimIndent()
        val games = Pgn.parse(pgn)
        assertEquals(1, games.size)
        val g = games[0]
        assertNull(g.error)
        assertEquals(33, g.moves.size)
        val game = g.toGame().toEnd()
        assertTrue(game.position.isCheckmate)
        assertEquals("Rd8#", game.sans.last())
        val exported = Pgn.export(game)
        assertTrue(exported.contains("[White \"Morphy, Paul\"]"))
        assertTrue(exported.contains("17. Rd8# 1-0"))
        val again = Pgn.parse(exported)
        assertEquals(g.moves, again[0].moves)
    }

    @Test fun pgnMultipleGamesAndFen() {
        val pgn = """
            [Event "A"]
            [Result "*"]

            1. d4 d5 *

            [Event "B"]
            [SetUp "1"]
            [FEN "4k3/8/8/8/8/8/4P3/4K3 b - - 0 40"]

            40... Kd7 41. e4 1/2-1/2
        """.trimIndent()
        val games = Pgn.parse(pgn)
        assertEquals(2, games.size)
        assertEquals(2, games[0].moves.size)
        assertEquals(2, games[1].moves.size)
        val g = games[1].toGame()
        val out = Pgn.export(g)
        assertTrue(out.contains("[FEN \"4k3/8/8/8/8/8/4P3/4K3 b - - 0 40\"]"))
        assertTrue(out.contains("40... Kd7 41. e4"))
    }

    @Test fun pgnErrorReported() {
        val games = Pgn.parse("1. e4 e5 2. Ke3 Nc6")
        assertNotNull(games[0].error)
        assertEquals(2, games[0].moves.size)
    }

    @Test fun gameUndoRedoAndBranching() {
        var g = Game.new()
        for (u in listOf("e2e4", "e7e5", "g1f3")) g = g.play(Move.fromUci(u)!!)
        g = g.back().back()
        assertEquals(1, g.ply)
        assertTrue(g.canForward)
        g = g.forward()
        assertEquals("e5", g.sans[g.ply - 1])
        g = g.back().play(Move.fromUci("c7c5")!!)
        assertEquals(2, g.size)
        assertEquals("c5", g.sans.last())
    }

    @Test fun threefold() {
        var g = Game.new()
        repeat(2) {
            for (u in listOf("g1f3", "g8f6", "f3g1", "f6g8")) g = g.play(Move.fromUci(u)!!)
        }
        assertEquals(OutcomeReason.THREEFOLD_REPETITION, g.outcome()?.reason)
    }
}
