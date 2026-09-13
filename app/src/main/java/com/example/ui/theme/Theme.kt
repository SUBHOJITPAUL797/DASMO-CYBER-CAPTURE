package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val StudioColorScheme = darkColorScheme(
    primary = StudioPrimary,
    onPrimary = StudioTextPrimary,
    primaryContainer = StudioPrimaryDim,
    onPrimaryContainer = StudioPrimary,
    secondary = StudioAccent,
    onSecondary = StudioTextPrimary,
    secondaryContainer = StudioSurfaceElevated,
    onSecondaryContainer = StudioTextPrimary,
    tertiary = StudioSuccess,
    onTertiary = StudioTextPrimary,
    error = StudioDanger,
    onError = StudioTextPrimary,
    background = StudioDarkBg,
    onBackground = StudioTextPrimary,
    surface = StudioDark,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurface,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Preserve distinctive cyber cyberpunk aesthetic
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}

