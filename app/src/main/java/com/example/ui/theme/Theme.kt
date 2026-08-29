package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CyberColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = CyberBlack,
    primaryContainer = CyberBlue,
    onPrimaryContainer = CyberTextPrimary,
    secondary = CyberCyanDim,
    onSecondary = CyberBlack,
    secondaryContainer = CyberSurfaceVariant,
    onSecondaryContainer = CyberCyan,
    tertiary = CyberGreen,
    onTertiary = CyberBlack,
    error = CyberRed,
    onError = CyberTextPrimary,
    background = CyberBlack,
    onBackground = CyberTextPrimary,
    surface = CyberDark,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurface,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Preserve distinctive cyber cyberpunk aesthetic
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        typography = Typography,
        content = content
    )
}

