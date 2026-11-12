package com.stockfishmobile.chess

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Result of parsing one game from PGN. [error] is set if parsing stopped early. */
data class PgnGame(
    val tags: Map<String, String>,
    val start: Position,
    val moves: List<Move>,
    val error: String? = null,
) {
    val title: String
        get() {
            val w = tags["White"]?.takeIf { it.isNotBlank() && it != "?" } ?: "?"
            val b = tags["Black"]?.takeIf { it.isNotBlank() && it != "?" } ?: "?"
            val r = tags["Result"] ?: "*"
            return "$w – $b  $r"
        }

    fun toGame(): Game = Game.fromMoves(start, moves, tags).toStart()
}

class PgnException(message: String) : Exception(message)

object Pgn {
    private val RESULTS = setOf("1-0", "0-1", "1/2-1/2", "½-½", "*")
    private val MOVE_NUMBER = Regex("^\\d+\\.*")

    /**
     * Parses all games in a PGN text. Only the main line is imported;
     * comments, NAGs and variations are skipped.
     */
    fun parse(text: String): List<PgnGame> {
        val games = ArrayList<PgnGame>()
        val s = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        val n = s.length

        var tags = LinkedHashMap<String, String>()
        val sanTokens = ArrayList<String>()
        var inMoves = false

        fun finishGame() {
            if (tags.isEmpty() && sanTokens.isEmpty()) return
            games.add(buildGame(tags, sanTokens))
            tags = LinkedHashMap()
            sanTokens.clear()
            inMoves = false
        }

        var lineStart = true
        while (i < n) {
            val c = s[i]
            when {
                c == '\n' -> { lineStart = true; i++; continue }
                c.isWhitespace() -> { i++; continue }
                c == '%' && lineStart -> { // escape line
                    while (i < n && s[i] != '\n') i++
                    continue
                }
                c == '[' -> {
                    if (inMoves) finishGame()
                    val end = findTagEnd(s, i)
                    parseTag(s.substring(i + 1, end))?.let { (k, v) -> tags[k] = v }
                    i = end + 1
                }
                c == '{' -> {
                    val end = s.indexOf('}', i + 1)
                    i = if (end < 0) n else end + 1
                }
                c == ';' -> {
                    while (i < n && s[i] != '\n') i++
                }
                c == '(' -> {
                    // Skip a (possibly nested) variation, honouring comments inside it.
                    var depth = 0
                    while (i < n) {
                        when (s[i]) {
                            '(' -> depth++
                            ')' -> { depth--; if (depth == 0) { i++; break } }
                            '{' -> { val e = s.indexOf('}', i + 1); i = if (e < 0) n - 1 else e }
                            ';' -> { while (i < n - 1 && s[i + 1] != '\n') i++ }
                        }
                        i++
                    }
                    inMoves = true
                }
                c == ')' -> i++
                else -> {
                    val start = i
                    while (i < n && !s[i].isWhitespace() && s[i] !in "{}()[];") i++
                    var token = s.substring(start, i)
                    inMoves = true
                    if (token in RESULTS) {
                        if (!tags.containsKey("Result") || tags["Result"] == "*") tags["Result"] = token.replace("½-½", "1/2-1/2")
                        finishGame()
                    } else if (token.startsWith("$")) {
                        // NAG
                    } else {
                        token = token.replace(MOVE_NUMBER, "")
                        if (token.isNotEmpty() && token != "..") sanTokens.add(token)
                    }
                }
            }
            lineStart = false
        }
        finishGame()
        return games
    }

    private fun findTagEnd(s: String, from: Int): Int {
        var i = from + 1
        var inQuotes = false
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes) { i += 2; continue }
            if (c == '"') inQuotes = !inQuotes
            if (c == ']' && !inQuotes) return i
            if (c == '\n' && !inQuotes) return i - 1
            i++
        }
        return s.length - 1
    }

    private fun parseTag(body: String): Pair<String, String>? {
        val m = Regex("^\\s*([A-Za-z0-9_]+)\\s+\"(.*)\"\\s*$", RegexOption.DOT_MATCHES_ALL).find(body) ?: return null
        val value = m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
        return m.groupValues[1] to value
    }

    private fun buildGame(tags: Map<String, String>, tokens: List<String>): PgnGame {
        val fen = tags["FEN"]
        val start = try {
            if (fen != null) Position.fromFen(fen) else Position.START
        } catch (e: IllegalFenException) {
            return PgnGame(tags, Position.START, emptyList(), "Некорректный FEN: ${e.message}")
        }
        var pos = start
        val moves = ArrayList<Move>()
        for (t in tokens) {
            val m = San.parse(pos, t)
            if (m == null) {
                val num = pos.fullmoveNumber
                val dots = if (pos.sideToMove == Side.WHITE) "." else "..."
                return PgnGame(tags, start, moves, "Не удалось разобрать ход $num$dots $t")
            }
            moves.add(m)
            pos = pos.play(m)
        }
        return PgnGame(tags, start, moves)
    }

    /** Exports the whole main line of [game] (regardless of the cursor). */
    fun export(game: Game): String {
        val tags = LinkedHashMap<String, String>()
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        val defaults = listOf(
            "Event" to "Analysis", "Site" to "Stockfish Mobile", "Date" to today,
            "Round" to "-", "White" to "?", "Black" to "?",
        )
        for ((k, v) in defaults) tags[k] = game.tags[k]?.takeIf { it.isNotBlank() } ?: v
        val end = game.goTo(game.size)
        val result = end.outcome()?.result ?: game.tags["Result"]?.takeIf { it in RESULTS } ?: "*"
        tags["Result"] = result
        if (!game.isStandardStart) {
            tags["SetUp"] = "1"
            tags["FEN"] = game.startPosition.toFen()
        }
        for ((k, v) in game.tags) if (k !in tags && k != "SetUp" && k != "FEN") tags[k] = v

        val sb = StringBuilder()
        for ((k, v) in tags) sb.append('[').append(k).append(" \"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]\n")
        sb.append('\n')

        val tokens = ArrayList<String>()
        for (i in game.sans.indices) {
            val white = game.isWhiteMove(i)
            if (white) tokens.add("${game.moveNumberOf(i)}.")
            else if (i == 0) tokens.add("${game.moveNumberOf(i)}...")
            tokens.add(game.sans[i])
        }
        tokens.add(result)
        var line = StringBuilder()
        for (t in tokens) {
            if (line.isNotEmpty() && line.length + 1 + t.length > 79) {
                sb.append(line).append('\n')
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(t)
        }
        sb.append(line).append('\n')
        return sb.toString()
    }
}
