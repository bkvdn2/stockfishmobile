package com.stockfishmobile.chess

/** Standard Algebraic Notation encoding and (lenient) decoding. */
object San {

    /** Returns the SAN of a legal [move] in [pos], including +/# suffix. */
    fun toSan(pos: Position, move: Move): String {
        val base = toSanNoSuffix(pos, move)
        val next = pos.play(move)
        return when {
            next.isCheckmate -> "$base#"
            next.isCheck -> "$base+"
            else -> base
        }
    }

    private fun toSanNoSuffix(pos: Position, move: Move): String {
        val piece = pos[move.from] ?: error("No piece on ${Sq.name(move.from)}")
        if (pos.isCastling(move)) {
            return if (Sq.file(move.to) == 6) "O-O" else "O-O-O"
        }
        val capture = pos.isCapture(move)
        val sb = StringBuilder()
        if (piece.type == PieceType.PAWN) {
            if (capture) sb.append('a' + Sq.file(move.from)).append('x')
            sb.append(Sq.name(move.to))
            move.promotion?.let { sb.append('=').append(it.sanLetter) }
            return sb.toString()
        }
        sb.append(piece.type.sanLetter)
        // Disambiguation among other pieces of the same type reaching the same square.
        val others = pos.legalMoves.filter {
            it.to == move.to && it.from != move.from && pos[it.from]?.type == piece.type
        }
        if (others.isNotEmpty()) {
            val sameFile = others.any { Sq.file(it.from) == Sq.file(move.from) }
            val sameRank = others.any { Sq.rank(it.from) == Sq.rank(move.from) }
            when {
                !sameFile -> sb.append('a' + Sq.file(move.from))
                !sameRank -> sb.append('1' + Sq.rank(move.from))
                else -> sb.append(Sq.name(move.from))
            }
        }
        if (capture) sb.append('x')
        sb.append(Sq.name(move.to))
        return sb.toString()
    }

    private val SAN_REGEX = Regex("^([NBRQK])?([a-h])?([1-8])?(x|:)?([a-h][1-8])(?:=?([NBRQnbrq]))?$")

    /**
     * Parses a SAN token (tolerating annotations like !?, missing '=', over-
     * disambiguation, 0-0 castling and UCI/long notation). Returns null if the
     * token does not denote exactly one legal move.
     */
    fun parse(pos: Position, token: String): Move? {
        var s = token.trim()
            .trimEnd('+', '#', '!', '?')
            .replace("‒", "-").replace("–", "-")
        if (s.endsWith("e.p.")) s = s.removeSuffix("e.p.").trim()
        if (s.isEmpty()) return null

        when (s.uppercase().replace('0', 'O')) {
            "O-O", "OO" -> return pos.legalMoves.firstOrNull { pos.isCastling(it) && Sq.file(it.to) == 6 }
            "O-O-O", "OOO" -> return pos.legalMoves.firstOrNull { pos.isCastling(it) && Sq.file(it.to) == 2 }
        }

        // Coordinate notation: e2e4, e7e8q, Ng1-f3, e2-e4.
        val coord = Regex("^[NBRQK]?([a-h][1-8])[-x]?([a-h][1-8])=?([nbrqNBRQ])?$").find(s)
        if (coord != null) {
            val (f, t, p) = coord.destructured
            val promo = if (p.isEmpty()) null else PieceType.fromLetter(p[0])
            val m = Move(Sq.parse(f), Sq.parse(t), promo)
            if (pos.isLegal(m)) return m
        }

        val match = SAN_REGEX.find(s) ?: return null
        val (pieceL, fromFile, fromRank, _, target, promoL) = match.destructured
        val type = if (pieceL.isEmpty()) PieceType.PAWN else PieceType.fromLetter(pieceL[0])!!
        val to = Sq.parse(target)
        val promo = if (promoL.isEmpty()) null else PieceType.fromLetter(promoL[0])
        val candidates = pos.legalMoves.filter { m ->
            val p = pos[m.from]
            p != null && p.type == type && m.to == to &&
                (fromFile.isEmpty() || Sq.file(m.from) == fromFile[0] - 'a') &&
                (fromRank.isEmpty() || Sq.rank(m.from) == fromRank[0] - '1') &&
                (if (type == PieceType.PAWN && pos.isPromotionMove(m.from, m.to)) m.promotion == (promo ?: PieceType.QUEEN) else m.promotion == null)
        }
        return candidates.singleOrNull()
    }
}
