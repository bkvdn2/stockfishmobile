package com.stockfishmobile.chess

import kotlin.math.abs

/**
 * Immutable chess position with full legal move generation
 * (castling, en passant, promotion, check/pin handling).
 */
class Position private constructor(
    private val board: Array<Piece?>,
    val sideToMove: Side,
    /** Bit mask of [WK], [WQ], [BK], [BQ]. */
    val castling: Int,
    /** En passant target square (square jumped over) or [Sq.NONE]. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    operator fun get(sq: Int): Piece? = board[sq]

    val legalMoves: List<Move> by lazy { generateLegal() }

    val isCheck: Boolean by lazy { isSquareAttacked(board, kingSquare(board, sideToMove), sideToMove.opposite) }
    val isCheckmate: Boolean get() = isCheck && legalMoves.isEmpty()
    val isStalemate: Boolean get() = !isCheck && legalMoves.isEmpty()

    fun kingSquare(side: Side): Int = kingSquare(board, side)

    fun isLegal(move: Move): Boolean = legalMoves.contains(move)

    fun legalMovesFrom(sq: Int): List<Move> = legalMoves.filter { it.from == sq }

    /** True if the move is a castling move (king moves two files). */
    fun isCastling(move: Move): Boolean {
        val p = board[move.from] ?: return false
        return p.type == PieceType.KING && abs(Sq.file(move.from) - Sq.file(move.to)) == 2
    }

    fun isEnPassant(move: Move): Boolean {
        val p = board[move.from] ?: return false
        return p.type == PieceType.PAWN && move.to == epSquare && Sq.file(move.from) != Sq.file(move.to) && board[move.to] == null
    }

    fun isCapture(move: Move): Boolean = board[move.to] != null || isEnPassant(move)

    fun isPromotionMove(from: Int, to: Int): Boolean {
        val p = board[from] ?: return false
        if (p.type != PieceType.PAWN) return false
        val r = Sq.rank(to)
        return (p.side == Side.WHITE && r == 7) || (p.side == Side.BLACK && r == 0)
    }

    /**
     * Plays a move and returns the resulting position. The move must be legal;
     * use [isLegal] or pick it from [legalMoves].
     */
    fun play(move: Move): Position {
        require(isLegal(move)) { "Illegal move ${move.uci()} in ${toFen()}" }
        return playUnchecked(move)
    }

    private fun playUnchecked(move: Move): Position {
        val nb = board.copyOf()
        val piece = nb[move.from]!!
        val captured = nb[move.to]
        val ep = isEnPassant(move)
        applyMove(nb, move, piece, ep)

        var rights = castling
        fun touch(sq: Int) {
            rights = when (sq) {
                4 -> rights and (WK or WQ).inv()
                0 -> rights and WQ.inv()
                7 -> rights and WK.inv()
                60 -> rights and (BK or BQ).inv()
                56 -> rights and BQ.inv()
                63 -> rights and BK.inv()
                else -> rights
            }
        }
        touch(move.from)
        touch(move.to)

        var newEp = Sq.NONE
        if (piece.type == PieceType.PAWN && abs(move.to - move.from) == 16) {
            newEp = (move.from + move.to) / 2
        }
        val resetClock = piece.type == PieceType.PAWN || captured != null || ep
        return Position(
            nb,
            sideToMove.opposite,
            rights,
            newEp,
            if (resetClock) 0 else halfmoveClock + 1,
            if (sideToMove == Side.BLACK) fullmoveNumber + 1 else fullmoveNumber,
        )
    }

    // ------------------------------------------------------------------ FEN

    fun toFen(): String = buildString {
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = board[Sq.of(file, rank)]
                if (p == null) {
                    empty++
                } else {
                    if (empty > 0) append(empty).also { empty = 0 }
                    append(p.fenChar)
                }
            }
            if (empty > 0) append(empty)
            if (rank > 0) append('/')
        }
        append(if (sideToMove == Side.WHITE) " w " else " b ")
        if (castling == 0) append('-') else {
            if (castling and WK != 0) append('K')
            if (castling and WQ != 0) append('Q')
            if (castling and BK != 0) append('k')
            if (castling and BQ != 0) append('q')
        }
        append(' ')
        append(if (hasLegalEnPassant()) Sq.name(epSquare) else "-")
        append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
    }

    /** Key used for repetition detection (FEN without move counters). */
    fun repetitionKey(): String = toFen().split(' ').take(4).joinToString(" ")

    private fun hasLegalEnPassant(): Boolean =
        epSquare != Sq.NONE && legalMoves.any { it.to == epSquare && isEnPassant(it) }

    /** Whether neither side can possibly checkmate (dead position by material). */
    fun isInsufficientMaterial(): Boolean {
        val pieces = board.withIndex().filter { it.value != null && it.value!!.type != PieceType.KING }
        if (pieces.isEmpty()) return true
        if (pieces.any { it.value!!.type in setOf(PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN) }) return false
        if (pieces.size == 1) return true // single minor piece
        // Only bishops, all on the same square colour.
        if (pieces.all { it.value!!.type == PieceType.BISHOP }) {
            val colours = pieces.map { (Sq.file(it.index) + Sq.rank(it.index)) % 2 }.toSet()
            return colours.size == 1
        }
        return false
    }

    // ------------------------------------------------------------------ move generation

    private fun generateLegal(): List<Move> {
        val result = ArrayList<Move>(48)
        val us = sideToMove
        val them = us.opposite
        for (m in generatePseudo()) {
            val nb = board.copyOf()
            applyMove(nb, m, nb[m.from]!!, isEnPassant(m))
            if (!isSquareAttacked(nb, kingSquare(nb, us), them)) result.add(m)
        }
        return result
    }

    private fun generatePseudo(): List<Move> {
        val moves = ArrayList<Move>(64)
        val us = sideToMove
        for (from in 0 until 64) {
            val p = board[from] ?: continue
            if (p.side != us) continue
            val f = Sq.file(from)
            val r = Sq.rank(from)
            when (p.type) {
                PieceType.PAWN -> genPawn(moves, from, f, r, us)
                PieceType.KNIGHT -> genSteps(moves, from, f, r, us, KNIGHT_STEPS)
                PieceType.BISHOP -> genSlides(moves, from, f, r, us, DIAGONALS)
                PieceType.ROOK -> genSlides(moves, from, f, r, us, ORTHOGONALS)
                PieceType.QUEEN -> {
                    genSlides(moves, from, f, r, us, DIAGONALS)
                    genSlides(moves, from, f, r, us, ORTHOGONALS)
                }
                PieceType.KING -> {
                    genSteps(moves, from, f, r, us, KING_STEPS)
                    genCastling(moves, from, us)
                }
            }
        }
        return moves
    }

    private fun addPawnMove(moves: MutableList<Move>, from: Int, to: Int, us: Side) {
        val lastRank = if (us == Side.WHITE) 7 else 0
        if (Sq.rank(to) == lastRank) {
            for (t in PROMOTIONS) moves.add(Move(from, to, t))
        } else {
            moves.add(Move(from, to))
        }
    }

    private fun genPawn(moves: MutableList<Move>, from: Int, f: Int, r: Int, us: Side) {
        val dir = if (us == Side.WHITE) 1 else -1
        val startRank = if (us == Side.WHITE) 1 else 6
        val r1 = r + dir
        if (r1 !in 0..7) return
        val one = Sq.of(f, r1)
        if (board[one] == null) {
            addPawnMove(moves, from, one, us)
            if (r == startRank) {
                val two = Sq.of(f, r + 2 * dir)
                if (board[two] == null) moves.add(Move(from, two))
            }
        }
        for (df in intArrayOf(-1, 1)) {
            val nf = f + df
            if (nf !in 0..7) continue
            val to = Sq.of(nf, r1)
            val target = board[to]
            if (target != null && target.side != us) {
                addPawnMove(moves, from, to, us)
            } else if (target == null && to == epSquare) {
                moves.add(Move(from, to))
            }
        }
    }

    private fun genSteps(moves: MutableList<Move>, from: Int, f: Int, r: Int, us: Side, steps: Array<IntArray>) {
        for (s in steps) {
            val nf = f + s[0]
            val nr = r + s[1]
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = Sq.of(nf, nr)
            val t = board[to]
            if (t == null || t.side != us) moves.add(Move(from, to))
        }
    }

    private fun genSlides(moves: MutableList<Move>, from: Int, f: Int, r: Int, us: Side, dirs: Array<IntArray>) {
        for (d in dirs) {
            var nf = f + d[0]
            var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val to = Sq.of(nf, nr)
                val t = board[to]
                if (t == null) {
                    moves.add(Move(from, to))
                } else {
                    if (t.side != us) moves.add(Move(from, to))
                    break
                }
                nf += d[0]
                nr += d[1]
            }
        }
    }

    private fun genCastling(moves: MutableList<Move>, from: Int, us: Side) {
        val them = us.opposite
        if (us == Side.WHITE && from == 4) {
            if (castling and WK != 0 && board[5] == null && board[6] == null &&
                board[7] == Piece.of(PieceType.ROOK, Side.WHITE) &&
                !isSquareAttacked(board, 4, them) && !isSquareAttacked(board, 5, them) && !isSquareAttacked(board, 6, them)
            ) moves.add(Move(4, 6))
            if (castling and WQ != 0 && board[3] == null && board[2] == null && board[1] == null &&
                board[0] == Piece.of(PieceType.ROOK, Side.WHITE) &&
                !isSquareAttacked(board, 4, them) && !isSquareAttacked(board, 3, them) && !isSquareAttacked(board, 2, them)
            ) moves.add(Move(4, 2))
        } else if (us == Side.BLACK && from == 60) {
            if (castling and BK != 0 && board[61] == null && board[62] == null &&
                board[63] == Piece.of(PieceType.ROOK, Side.BLACK) &&
                !isSquareAttacked(board, 60, them) && !isSquareAttacked(board, 61, them) && !isSquareAttacked(board, 62, them)
            ) moves.add(Move(60, 62))
            if (castling and BQ != 0 && board[59] == null && board[58] == null && board[57] == null &&
                board[56] == Piece.of(PieceType.ROOK, Side.BLACK) &&
                !isSquareAttacked(board, 60, them) && !isSquareAttacked(board, 59, them) && !isSquareAttacked(board, 58, them)
            ) moves.add(Move(60, 58))
        }
    }

    companion object {
        const val WK = 1
        const val WQ = 2
        const val BK = 4
        const val BQ = 8

        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        val START: Position by lazy { fromFen(START_FEN) }

        private val PROMOTIONS = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
        private val KNIGHT_STEPS = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
        )
        private val KING_STEPS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )
        private val DIAGONALS = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
        private val ORTHOGONALS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

        private fun kingSquare(board: Array<Piece?>, side: Side): Int {
            val k = Piece.of(PieceType.KING, side)
            for (i in 0 until 64) if (board[i] == k) return i
            return Sq.NONE
        }

        /** Applies the board changes of a move in place (no legality checks). */
        private fun applyMove(nb: Array<Piece?>, move: Move, piece: Piece, enPassant: Boolean) {
            nb[move.from] = null
            if (enPassant) {
                val capSq = if (piece.side == Side.WHITE) move.to - 8 else move.to + 8
                nb[capSq] = null
            }
            nb[move.to] = if (move.promotion != null) Piece.of(move.promotion, piece.side) else piece
            if (piece.type == PieceType.KING && abs(move.to - move.from) == 2) {
                when (move.to) {
                    6 -> { nb[7] = null; nb[5] = Piece.of(PieceType.ROOK, Side.WHITE) }
                    2 -> { nb[0] = null; nb[3] = Piece.of(PieceType.ROOK, Side.WHITE) }
                    62 -> { nb[63] = null; nb[61] = Piece.of(PieceType.ROOK, Side.BLACK) }
                    58 -> { nb[56] = null; nb[59] = Piece.of(PieceType.ROOK, Side.BLACK) }
                }
            }
        }

        fun isSquareAttacked(board: Array<Piece?>, sq: Int, by: Side): Boolean {
            if (sq == Sq.NONE) return false
            val f = Sq.file(sq)
            val r = Sq.rank(sq)
            // Pawns
            val pr = if (by == Side.WHITE) r - 1 else r + 1
            if (pr in 0..7) {
                val pawn = Piece.of(PieceType.PAWN, by)
                if (f > 0 && board[Sq.of(f - 1, pr)] == pawn) return true
                if (f < 7 && board[Sq.of(f + 1, pr)] == pawn) return true
            }
            // Knights
            val knight = Piece.of(PieceType.KNIGHT, by)
            for (s in KNIGHT_STEPS) {
                val nf = f + s[0]; val nr = r + s[1]
                if (nf in 0..7 && nr in 0..7 && board[Sq.of(nf, nr)] == knight) return true
            }
            // King
            val king = Piece.of(PieceType.KING, by)
            for (s in KING_STEPS) {
                val nf = f + s[0]; val nr = r + s[1]
                if (nf in 0..7 && nr in 0..7 && board[Sq.of(nf, nr)] == king) return true
            }
            // Sliders
            val queen = Piece.of(PieceType.QUEEN, by)
            val bishop = Piece.of(PieceType.BISHOP, by)
            val rook = Piece.of(PieceType.ROOK, by)
            for (d in DIAGONALS) {
                var nf = f + d[0]; var nr = r + d[1]
                while (nf in 0..7 && nr in 0..7) {
                    val p = board[Sq.of(nf, nr)]
                    if (p != null) {
                        if (p == bishop || p == queen) return true
                        break
                    }
                    nf += d[0]; nr += d[1]
                }
            }
            for (d in ORTHOGONALS) {
                var nf = f + d[0]; var nr = r + d[1]
                while (nf in 0..7 && nr in 0..7) {
                    val p = board[Sq.of(nf, nr)]
                    if (p != null) {
                        if (p == rook || p == queen) return true
                        break
                    }
                    nf += d[0]; nr += d[1]
                }
            }
            return false
        }

        /** Parses and validates a FEN string. Move counters are optional. */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.size < 4) throw IllegalFenException("FEN must have at least 4 fields")
            val board = arrayOfNulls<Piece>(64)
            val ranks = parts[0].split('/')
            if (ranks.size != 8) throw IllegalFenException("FEN board must have 8 ranks")
            for ((i, rankStr) in ranks.withIndex()) {
                val rank = 7 - i
                var file = 0
                for (c in rankStr) {
                    if (c.isDigit()) {
                        file += c - '0'
                    } else {
                        val p = Piece.fromFenChar(c) ?: throw IllegalFenException("Bad piece '$c'")
                        if (file > 7) throw IllegalFenException("Rank ${rank + 1} too long")
                        board[Sq.of(file, rank)] = Piece.of(p.type, p.side)
                        file++
                    }
                }
                if (file != 8) throw IllegalFenException("Rank ${rank + 1} has $file squares")
            }
            val side = when (parts[1]) {
                "w" -> Side.WHITE
                "b" -> Side.BLACK
                else -> throw IllegalFenException("Bad side to move '${parts[1]}'")
            }
            var castling = 0
            if (parts[2] != "-") {
                for (c in parts[2]) {
                    castling = castling or when (c) {
                        'K' -> WK; 'Q' -> WQ; 'k' -> BK; 'q' -> BQ
                        else -> throw IllegalFenException("Bad castling field '${parts[2]}'")
                    }
                }
            }
            // Drop castling rights that do not match the piece placement.
            fun has(sq: Int, t: PieceType, s: Side) = board[sq] == Piece.of(t, s)
            if (!has(4, PieceType.KING, Side.WHITE)) castling = castling and (WK or WQ).inv()
            if (!has(7, PieceType.ROOK, Side.WHITE)) castling = castling and WK.inv()
            if (!has(0, PieceType.ROOK, Side.WHITE)) castling = castling and WQ.inv()
            if (!has(60, PieceType.KING, Side.BLACK)) castling = castling and (BK or BQ).inv()
            if (!has(63, PieceType.ROOK, Side.BLACK)) castling = castling and BK.inv()
            if (!has(56, PieceType.ROOK, Side.BLACK)) castling = castling and BQ.inv()

            var ep = if (parts[3] == "-") Sq.NONE else Sq.parse(parts[3])
            if (parts[3] != "-" && ep == Sq.NONE) throw IllegalFenException("Bad en passant square")
            if (ep != Sq.NONE) {
                val expectedRank = if (side == Side.WHITE) 5 else 2
                val pawnSq = if (side == Side.WHITE) ep - 8 else ep + 8
                if (Sq.rank(ep) != expectedRank || board[pawnSq] != Piece.of(PieceType.PAWN, side.opposite) || board[ep] != null) {
                    ep = Sq.NONE
                }
            }
            val half = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val wk = board.count { it == Piece.of(PieceType.KING, Side.WHITE) }
            val bk = board.count { it == Piece.of(PieceType.KING, Side.BLACK) }
            if (wk != 1 || bk != 1) throw IllegalFenException("Each side must have exactly one king")
            for (f in 0..7) {
                if (board[Sq.of(f, 0)]?.type == PieceType.PAWN || board[Sq.of(f, 7)]?.type == PieceType.PAWN) {
                    throw IllegalFenException("Pawns on the first or last rank")
                }
            }
            if (isSquareAttacked(board, kingSquare(board, side.opposite), side)) {
                throw IllegalFenException("The side not to move is in check")
            }
            return Position(board, side, castling, ep, half, full)
        }
    }
}
