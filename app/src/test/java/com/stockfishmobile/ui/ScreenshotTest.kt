package com.stockfishmobile.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.stockfishmobile.chess.Sq
import com.stockfishmobile.engine.StockfishInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the real UI with a real (host-built) Stockfish attached and writes
 * PNG screenshots to $SCREENSHOT_DIR. Requires SF_HOST_BINARY and SF_NNUE.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setup(): AnalysisViewModel {
        val bin = System.getenv("SF_HOST_BINARY")?.let(::File)
        val net = System.getenv("SF_NNUE")?.let(::File)
        assumeTrue(bin?.canExecute() == true && net?.exists() == true)
        AnalysisViewModel.installationOverride = { StockfishInstaller.Installation(bin!!, net!!, "host") }
        return AnalysisViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    /** Background engine threads post to the main looper; Robolectric needs it pumped explicitly. */
    private fun pump() {
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
    }

    private fun shot(name: String) {
        val dir = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(vm: AnalysisViewModel, vararg squares: String) {
        compose.runOnUiThread { for (s in squares) vm.onSquareTap(Sq.parse(s)) }
        compose.waitForIdle()
    }

    private fun waitDepth(vm: AnalysisViewModel, depth: Int) {
        compose.waitUntil(60_000) {
            pump()
            val a = vm.analysis.value
            a != null && a.depth >= depth && a.lines.size >= 3
        }
        compose.waitForIdle()
    }

    @Test
    fun mainScreenWithAnalysis() {
        val vm = setup()
        compose.setContent { AppTheme { MainScreen(vm) } }
        compose.runOnUiThread { vm.updateSettings { it.copy(depth = 16, multiPv = 3, threads = 2, hashMb = 32) } }
        // 1. e4 e5 2. Nf3 — entered by tapping, exactly like a user.
        tap(vm, "e2", "e4", "e7", "e5", "g1", "f3")
        assertEquals(3, vm.ui.value.game.size)
        waitDepth(vm, 16)
        // Select the b8 knight to show legal move hints.
        tap(vm, "b8")
        assertTrue(vm.ui.value.targets.isNotEmpty())
        shot("01_main_analysis")

        tap(vm, "b8") // deselect
        vm.analysis.value!!.best!!.let { best -> compose.runOnUiThread { vm.showPreview(best, step = 2) } }
        compose.waitForIdle()
        shot("02_preview_line")

        compose.runOnUiThread { vm.closePreview(); vm.back(); vm.back() }
        compose.waitForIdle()
        waitDepth(vm, 14)
        shot("03_after_undo")
    }

    @Test
    fun importedGameAndMate() {
        val vm = setup()
        compose.setContent { AppTheme { MainScreen(vm) } }
        compose.runOnUiThread {
            vm.updateSettings { it.copy(depth = 14, multiPv = 3, threads = 2, hashMb = 32, flipped = true) }
            vm.importText(
                """
                [White "Morphy"]
                [Black "Duke Karl / Count Isouard"]
                1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
                8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
                14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
                """.trimIndent(),
            )
            vm.goTo(30) // before 16. Qb8+ : engine should find mate in 2
        }
        compose.waitUntil(60_000) { pump(); vm.analysis.value?.best?.whiteMate != null && vm.analysis.value!!.lines.size >= 3 }
        compose.waitForIdle()
        assertEquals(2, vm.analysis.value!!.best!!.whiteMate)
        shot("04_imported_mate_found")

        compose.runOnUiThread { vm.toEnd() }
        compose.waitForIdle()
        shot("05_checkmate")
    }
}
