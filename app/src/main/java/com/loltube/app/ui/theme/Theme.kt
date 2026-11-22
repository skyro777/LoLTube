package com.loltube.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Amber-forward cinematic palette: near-black neutrals, warm accents.
private val Amber = Color(0xFFFFB300)
private val AmberLight = Color(0xFFFFD54F)
private val Orange = Color(0xFFFF7043)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF201400),
    primaryContainer = Color(0xFF3B2B00),
    onPrimaryContainer = Color(0xFFFFE6A6),
    secondary = AmberLight,
    onSecondary = Color(0xFF201400),
    secondaryContainer = Color(0xFF443510),
    onSecondaryContainer = Color(0xFFFFE5A0),
    tertiary = Orange,
    onTertiary = Color(0xFF2B1000),
    tertiaryContainer = Color(0xFF481F00),
    onTertiaryContainer = Color(0xFFFFDBC7),
    background = Color(0xFF0A0A0E),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF1B1B22),
    onSurfaceVariant = Color(0xFFB9B9C6),
    surfaceTint = Amber,
    inverseSurface = Color(0xFFE7E7EE),
    inverseOnSurface = Color(0xFF14141B),
    inversePrimary = Color(0xFF8A5E00),
    outline = Color(0xFF3A3A46),
    outlineVariant = Color(0xFF26262E),
    error = Color(0xFFFF5449),
    onError = Color(0xFF2A0700),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color(0xFF000000),
)

// Slightly rounder defaults so cards/buttons feel modern without per-site shapes.
private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun LoLTubeTheme(content: @Composable () -> Unit) {
    // video app - always dark for comfortable viewing
    isSystemInDarkTheme() // kept for future light-mode support
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        shapes = AppShapes,
        content = content,
    )
}
