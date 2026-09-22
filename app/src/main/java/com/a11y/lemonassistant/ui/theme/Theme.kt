package com.a11y.lemonassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = LemonGreen,
    onPrimary = DarkBackground,
    secondary = LemonDarkGreen,
    onSecondary = TextPrimaryHighContrast,
    background = DarkBackground,
    onBackground = TextPrimaryHighContrast,
    surface = DarkSurface,
    onSurface = TextPrimaryHighContrast,
    error = ErrorRed,
    onError = TextPrimaryHighContrast
)

@Composable
fun LemonAssistantTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
