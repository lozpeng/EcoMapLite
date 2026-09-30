

package com.combo.core.ui.theme


import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    background = BlueBackground,
    surface = BlueBackground,
    onSurface = BlueTextPrimary,
    onSurfaceVariant = BlueTextSecondary,
    error = BlueError,
    errorContainer = BlueErrorContainer,
    tertiary = BlueWarning,
    tertiaryContainer = BlueWarningContainer,
    onTertiaryContainer = BlueTextPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = ModernDarkPrimary,
    background = ModernDarkBackground,
    surface = ModernDarkSurface,
    onSurface = ModernDarkTextPrimary,
    onSurfaceVariant = ModernDarkTextSecondary,
    error = ModernDarkError,
    errorContainer = ModernDarkErrorContainer,
    tertiary = ModernDarkWarning,
    tertiaryContainer = ModernDarkWarningContainer,
    onTertiaryContainer = ModernDarkTextPrimary
)

@Composable
fun FrameworkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}