package com.stockfishmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val background = Color(0xFF121316)
    val surface = Color(0xFF1B1D21)
    val surfaceHigh = Color(0xFF24272C)
    val outline = Color(0xFF33363C)
    val text = Color(0xFFE8E9EB)
    val textMuted = Color(0xFF9A9EA6)
    val accent = Color(0xFF6FB3FF)
    val best = Color(0xFF3FBF7F)

    // Board
    val lightSquare = Color(0xFFDDE3EA)
    val darkSquare = Color(0xFF7D93AB)
    val lastMove = Color(0x7FF5D45E)
    val selected = Color(0x9946A0FF)
    val legalDot = Color(0x55101418)
    val check = Color(0xCCE53935)
    val bestMoveSquare = Color(0x6633D17A)
    val arrow = Color(0xCC27AE60)
    val arrowSecondary = Color(0x8C6FB3FF)
    val previewArrow = Color(0xCCF39C12)

    // Eval bar
    val evalWhite = Color(0xFFF1F1F1)
    val evalBlack = Color(0xFF2E3136)
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF07213A),
    secondary = Palette.best,
    background = Palette.background,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.textMuted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Palette.surfaceHigh,
    surfaceContainerLow = Palette.surface,
    outline = Palette.outline,
    outlineVariant = Palette.outline,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
