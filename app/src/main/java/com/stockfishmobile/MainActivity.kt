package com.stockfishmobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.stockfishmobile.ui.AnalysisViewModel
import com.stockfishmobile.ui.AppTheme
import com.stockfishmobile.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: AnalysisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Pause the engine while the app is not visible to save battery.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vm.onForeground()
            override fun onStop(owner: LifecycleOwner) = vm.onBackground()
        })
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                MainScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Imports a PGN shared to / opened with the app. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        val text = try {
            uri?.let { contentResolver.openInputStream(it)?.use { s -> s.readBytes().toString(Charsets.UTF_8) } }
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        } catch (e: Exception) {
            vm.showMessage("Не удалось открыть файл: ${e.message}")
            null
        }
        if (!text.isNullOrBlank()) vm.importText(text)
    }
}
