package dev.cloudsync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1B3A4B)
private val InkDark = Color(0xFF102833)
private val Teal = Color(0xFF1F7A6A)
private val Brass = Color(0xFFC4A46A)
private val Paper = Color(0xFFF6F1E7)
private val PaperCard = Color(0xFFFFFBF5)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E6EF),
    onPrimaryContainer = InkDark,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EFE8),
    tertiary = Color(0xFF6E5424),
    tertiaryContainer = Color(0xFFF3E4C4),
    background = Paper,
    onBackground = InkDark,
    surface = PaperCard,
    onSurface = InkDark,
    surfaceVariant = Color(0xFFE7E0D4),
    error = Color(0xFF8C2F2F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCBE0),
    onPrimary = InkDark,
    primaryContainer = Color(0xFF234556),
    secondary = Color(0xFF7DCFC2),
    onSecondary = InkDark,
    tertiary = Brass,
    background = InkDark,
    onBackground = Color(0xFFF6F1E7),
    surface = Color(0xFF16313F),
    onSurface = Color(0xFFF6F1E7),
    surfaceVariant = Color(0xFF234556),
    error = Color(0xFFFFB4AB),
)

@Composable
fun CloudSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
