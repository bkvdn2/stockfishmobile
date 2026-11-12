package com.stockfishmobile.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Locates the bundled Stockfish executable and prepares the NNUE network file.
 *
 * The engine binaries live in the APK as jniLibs (libstockfish*.so) so Android
 * extracts them into [android.content.pm.ApplicationInfo.nativeLibraryDir] — the
 * only place an app is allowed to execute files from on Android 10+.
 */
object StockfishInstaller {

    data class Installation(val binary: File, val network: File, val variant: String)

    fun install(context: Context): Installation {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val dotprod = File(libDir, "libstockfish_dotprod.so")
        val generic = File(libDir, "libstockfish.so")
        val useDotprod = dotprod.exists() && cpuSupportsDotProd()
        val binary = if (useDotprod) dotprod else generic
        if (!binary.exists()) {
            throw IOException("Движок не найден для ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        }
        val variant = when {
            useDotprod -> "armv8-dotprod"
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" -> "armv8"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        }
        return Installation(binary, extractNetwork(context), variant)
    }

    /** Copies the network from assets to internal storage once (it must be a real file for Stockfish). */
    private fun extractNetwork(context: Context): File {
        val assets = context.assets
        val name = assets.list("nnue")?.firstOrNull { it.endsWith(".nnue") }
            ?: throw IOException("Файл нейросети NNUE отсутствует в приложении")
        val dir = File(context.filesDir, "nnue").apply { mkdirs() }
        val target = File(dir, name)
        val expectedSize = try {
            assets.openFd("nnue/$name").use { it.length }
        } catch (_: IOException) {
            -1L
        }
        if (target.exists() && (expectedSize < 0 || target.length() == expectedSize)) return target

        // Remove networks of previous app versions.
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "$name.tmp")
        assets.open("nnue/$name").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить нейросеть")
        return target
    }

    /** ARMv8.2 dot-product instructions (asimddp) give a large NNUE speed-up. */
    private fun cpuSupportsDotProd(): Boolean = try {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .let { lines -> lines.isNotEmpty() && lines.all { " asimddp" in it || "\tasimddp" in it } }
    } catch (_: Exception) {
        false
    }

    fun cpuCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Number of threads that keeps the device responsive by default. */
    fun defaultThreads(): Int = (cpuCount() - 1).coerceIn(1, 6)

    /** Hash size chosen from the device RAM. */
    fun defaultHashMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = info.totalMem / (1024.0 * 1024 * 1024)
        return when {
            gb < 3 -> 32
            gb < 5 -> 64
            gb < 7 -> 128
            else -> 256
        }
    }

    fun maxHashMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val quarter = (info.totalMem / (1024 * 1024) / 4).toInt()
        return quarter.coerceIn(64, 1024)
    }
}
