package com.example.aiaccounting.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3478C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9F2FF),
    onPrimaryContainer = Color(0xFF234870),
    secondary = Color(0xFF496585),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9F2FF),
    onSecondaryContainer = Color(0xFF234870),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF202F43),
    surface = Color.White,
    onSurface = Color(0xFF202F43),
    onSurfaceVariant = Color(0xFF64748A),
    outlineVariant = Color(0xFFE3E9F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BBCF4),
    onPrimary = Color(0xFF12283F),
    primaryContainer = Color(0xFF283D57),
    onPrimaryContainer = Color(0xFFCCE1F9),
    secondary = Color(0xFFADBFD8),
    onSecondary = Color(0xFF1D324C),
    secondaryContainer = Color(0xFF283D57),
    onSecondaryContainer = Color(0xFFCCE1F9),
    background = Color(0xFF151B24),
    onBackground = Color(0xFFECF2FB),
    surface = Color(0xFF212A37),
    onSurface = Color(0xFFECF2FB),
    onSurfaceVariant = Color(0xFFADBDD2),
    outlineVariant = Color(0xFF394657),
)

@Composable
fun AIAccountingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
