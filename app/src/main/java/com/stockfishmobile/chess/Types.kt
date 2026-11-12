package com.stockfishmobile.chess

/** Square index: a1 = 0, b1 = 1, ..., h8 = 63. */
object Sq {
    const val NONE = -1

    fun of(file: Int, rank: Int): Int = rank * 8 + file
    fun file(sq: Int): Int = sq and 7
    fun rank(sq: Int): Int = sq shr 3
    fun name(sq: Int): String = "${'a' + file(sq)}${'1' + rank(sq)}"

    fun parse(s: String): Int {
        if (s.length != 2) return NONE
        val f = s[0] - 'a'
        val r = s[1] - '1'
        return if (f in 0..7 && r in 0..7) of(f, r) else NONE
    }
}

enum class Side {
    WHITE, BLACK;

    val opposite: Side get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val letter: Char) {
    PAWN('p'), KNIGHT('n'), BISHOP('b'), ROOK('r'), QUEEN('q'), KING('k');

    val sanLetter: String get() = if (this == PAWN) "" else letter.uppercaseChar().toString()

    companion object {
        fun fromLetter(c: Char): PieceType? = entries.firstOrNull { it.letter == c.lowercaseChar() }
    }
}

data class Piece(val type: PieceType, val side: Side) {
    /** FEN character: uppercase for white. */
    val fenChar: Char get() = if (side == Side.WHITE) type.letter.uppercaseChar() else type.letter

    companion object {
        fun fromFenChar(c: Char): Piece? {
            val t = PieceType.fromLetter(c) ?: return null
            return Piece(t, if (c.isUpperCase()) Side.WHITE else Side.BLACK)
        }

        // Shared instances to avoid allocations in move generation.
        private val cache = Array(2) { s -> Array(6) { t -> Piece(PieceType.entries[t], Side.entries[s]) } }
        fun of(type: PieceType, side: Side): Piece = cache[side.ordinal][type.ordinal]
    }
}

/**
 * A chess move in coordinate form. Castling is encoded as the king moving two
 * squares (e1g1), exactly like the UCI protocol for standard chess.
 */
data class Move(val from: Int, val to: Int, val promotion: PieceType? = null) {
    fun uci(): String = Sq.name(from) + Sq.name(to) + (promotion?.letter?.toString() ?: "")

    override fun toString(): String = uci()

    companion object {
        fun fromUci(s: String): Move? {
            if (s.length !in 4..5) return null
            val from = Sq.parse(s.substring(0, 2))
            val to = Sq.parse(s.substring(2, 4))
            if (from == Sq.NONE || to == Sq.NONE) return null
            val promo = if (s.length == 5) {
                PieceType.fromLetter(s[4])?.takeIf { it != PieceType.PAWN && it != PieceType.KING } ?: return null
            } else null
            return Move(from, to, promo)
        }
    }
}

class IllegalFenException(message: String) : IllegalArgumentException(message)
