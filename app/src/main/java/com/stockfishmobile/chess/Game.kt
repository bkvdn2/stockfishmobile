package com.stockfishmobile.chess

/**
 * Immutable game record: a start position, the main line of moves and a cursor
 * ([ply]) pointing at the position currently shown. Moves after the cursor are
 * kept so they can be redone; playing a different move from an earlier position
 * replaces them.
 */
class Game private constructor(
    val startPosition: Position,
    val moves: List<Move>,
    val sans: List<String>,
    private val positions: List<Position>,
    val ply: Int,
    val tags: Map<String, String>,
) {
    val position: Position get() = positions[ply]
    val size: Int get() = moves.size
    val lastMove: Move? get() = if (ply > 0) moves[ply - 1] else null
    val canBack: Boolean get() = ply > 0
    val canForward: Boolean get() = ply < moves.size
    val isStandardStart: Boolean get() = startPosition.toFen() == Position.START_FEN

    fun positionAt(index: Int): Position = positions[index]

    /** Plays [move] from the current position. */
    fun play(move: Move): Game {
        if (ply < moves.size && moves[ply] == move) return goTo(ply + 1)
        val pos = position
        require(pos.isLegal(move)) { "Illegal move ${move.uci()}" }
        val san = San.toSan(pos, move)
        val next = pos.play(move)
        return Game(
            startPosition,
            moves.subList(0, ply) + move,
            sans.subList(0, ply) + san,
            positions.subList(0, ply + 1) + next,
            ply + 1,
            tags,
        )
    }

    fun goTo(index: Int): Game =
        Game(startPosition, moves, sans, positions, index.coerceIn(0, moves.size), tags)

    fun back(): Game = goTo(ply - 1)
    fun forward(): Game = goTo(ply + 1)
    fun toStart(): Game = goTo(0)
    fun toEnd(): Game = goTo(moves.size)

    fun withTags(newTags: Map<String, String>): Game = Game(startPosition, moves, sans, positions, ply, newTags)

    /** UCI moves from the start position to the current position. */
    fun uciMovesToCurrent(): List<String> = moves.subList(0, ply).map { it.uci() }

    /** Move number label for the move at [index] (0-based): "1." or "1...". */
    fun moveNumberOf(index: Int): Int {
        val startPly = (startPosition.fullmoveNumber - 1) * 2 + if (startPosition.sideToMove == Side.BLACK) 1 else 0
        return (startPly + index) / 2 + 1
    }

    fun isWhiteMove(index: Int): Boolean = positions[index].sideToMove == Side.WHITE

    /** Whether the current position occurred at least three times in the line so far. */
    fun isThreefoldRepetition(): Boolean {
        val key = position.repetitionKey()
        var count = 0
        // Only positions since the last irreversible move can repeat.
        val from = (ply - position.halfmoveClock).coerceAtLeast(0)
        for (i in from..ply) if (positions[i].repetitionKey() == key) count++
        return count >= 3
    }

    /** Final state of the current position, or null if the game goes on. */
    fun outcome(): Outcome? {
        val pos = position
        return when {
            pos.isCheckmate -> Outcome(if (pos.sideToMove == Side.WHITE) "0-1" else "1-0", OutcomeReason.CHECKMATE)
            pos.isStalemate -> Outcome("1/2-1/2", OutcomeReason.STALEMATE)
            pos.isInsufficientMaterial() -> Outcome("1/2-1/2", OutcomeReason.INSUFFICIENT_MATERIAL)
            isThreefoldRepetition() -> Outcome("1/2-1/2", OutcomeReason.THREEFOLD_REPETITION)
            pos.halfmoveClock >= 100 -> Outcome("1/2-1/2", OutcomeReason.FIFTY_MOVES)
            else -> null
        }
    }

    companion object {
        fun new(start: Position = Position.START, tags: Map<String, String> = emptyMap()): Game =
            Game(start, emptyList(), emptyList(), listOf(start), 0, tags)

        /** Builds a game from a start position and a list of legal moves. */
        fun fromMoves(start: Position, moves: List<Move>, tags: Map<String, String> = emptyMap()): Game {
            var g = new(start, tags)
            for (m in moves) g = g.play(m)
            return g
        }
    }
}

enum class OutcomeReason { CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL, THREEFOLD_REPETITION, FIFTY_MOVES }

data class Outcome(val result: String, val reason: OutcomeReason)
