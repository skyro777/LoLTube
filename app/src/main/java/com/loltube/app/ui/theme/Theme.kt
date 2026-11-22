package com.loltube.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB300)
private val AmberLight = Color(0xFFFFD54F)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1200),
    secondary = AmberLight,
    onSecondary = Color(0xFF1A1200),
    tertiary = Color(0xFFFF7043),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF1D1D25),
    onSurfaceVariant = Color(0xFFB9B9C6),
    error = Color(0xFFFF5449),
    outline = Color(0xFF3A3A46),
)

@Composable
fun LoLTubeTheme(content: @Composable () -> Unit) {
    // video app - always dark for comfortable viewing
    isSystemInDarkTheme() // kept for future light-mode support
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
