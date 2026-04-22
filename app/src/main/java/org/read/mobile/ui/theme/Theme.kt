package org.read.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BurntOrange,
    onPrimary = Cream,
    primaryContainer = Cocoa,
    onPrimaryContainer = Cream,
    secondaryContainer = SlateBlue,
    onSecondaryContainer = SlateInk,
    tertiaryContainer = Sand,
    onTertiaryContainer = Walnut,
    surface = Cream,
    onSurface = Walnut,
    surfaceVariant = MistBlue,
    onSurfaceVariant = SlateInk,
    background = Paper,
    onBackground = Walnut,
    outline = Color(0xFFB7C2CC)
)

private val DarkColors = darkColorScheme(
    primary = Sand,
    onPrimary = Walnut,
    primaryContainer = Color(0xFF5D6370),
    onPrimaryContainer = Cream,
    secondaryContainer = Color(0xFF465462),
    onSecondaryContainer = Cream,
    tertiaryContainer = Color(0xFF495664),
    onTertiaryContainer = Cream,
    surface = NightSlate,
    onSurface = Cream,
    surfaceVariant = Color(0xFF2C3944),
    onSurfaceVariant = Color(0xFFD9E3EC),
    background = NightSlate,
    onBackground = Cream,
    outline = Color(0xFF8A99A8)
)

@Composable
fun LocalPdfReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
