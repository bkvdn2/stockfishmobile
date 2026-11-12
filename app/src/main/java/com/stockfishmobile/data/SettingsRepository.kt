package com.stockfishmobile.data

import android.content.Context
import com.stockfishmobile.engine.StockfishInstaller

data class AppSettings(
    /** Search depth; 0 = infinite analysis. */
    val depth: Int,
    val multiPv: Int,
    val threads: Int,
    val hashMb: Int,
    val autoAnalysis: Boolean,
    val flipped: Boolean,
    val showArrows: Boolean,
    val showCoordinates: Boolean,
) {
    val isInfinite: Boolean get() = depth == 0
}

/** Persists settings and the current game in SharedPreferences. */
class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        depth = prefs.getInt(KEY_DEPTH, DEFAULT_DEPTH),
        multiPv = prefs.getInt(KEY_MULTIPV, 3),
        threads = prefs.getInt(KEY_THREADS, StockfishInstaller.defaultThreads()).coerceIn(1, StockfishInstaller.cpuCount()),
        hashMb = prefs.getInt(KEY_HASH, StockfishInstaller.defaultHashMb(context)),
        autoAnalysis = prefs.getBoolean(KEY_AUTO, true),
        flipped = prefs.getBoolean(KEY_FLIPPED, false),
        showArrows = prefs.getBoolean(KEY_ARROWS, true),
        showCoordinates = prefs.getBoolean(KEY_COORDS, true),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt(KEY_DEPTH, s.depth)
            .putInt(KEY_MULTIPV, s.multiPv)
            .putInt(KEY_THREADS, s.threads)
            .putInt(KEY_HASH, s.hashMb)
            .putBoolean(KEY_AUTO, s.autoAnalysis)
            .putBoolean(KEY_FLIPPED, s.flipped)
            .putBoolean(KEY_ARROWS, s.showArrows)
            .putBoolean(KEY_COORDS, s.showCoordinates)
            .apply()
    }

    fun saveGame(pgn: String, ply: Int) {
        prefs.edit().putString(KEY_GAME, pgn).putInt(KEY_PLY, ply).apply()
    }

    fun loadGame(): Pair<String, Int>? {
        val pgn = prefs.getString(KEY_GAME, null) ?: return null
        return pgn to prefs.getInt(KEY_PLY, 0)
    }

    companion object {
        const val DEFAULT_DEPTH = 20
        private const val KEY_DEPTH = "depth"
        private const val KEY_MULTIPV = "multipv"
        private const val KEY_THREADS = "threads"
        private const val KEY_HASH = "hash"
        private const val KEY_AUTO = "auto"
        private const val KEY_FLIPPED = "flipped"
        private const val KEY_ARROWS = "arrows"
        private const val KEY_COORDS = "coords"
        private const val KEY_GAME = "game_pgn"
        private const val KEY_PLY = "game_ply"
    }
}
