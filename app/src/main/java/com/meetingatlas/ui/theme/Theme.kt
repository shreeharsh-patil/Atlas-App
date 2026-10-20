package com.meetingatlas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF202722)
private val Muted = Color(0xFF5A6660)
private val Green = Color(0xFF315B49)
private val Paper = Color(0xFFF5F5F0)
private val Dark = Color(0xFF171C19)

/**
 * Complete Material 3 scheme. Filling the extended surface roles keeps NavigationBar, dialogs,
 * bottom sheets and cards in the green brand palette instead of the lavender library baseline.
 */
private val LightColors = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9E0), onPrimaryContainer = Color(0xFF0E2A1F),
    inversePrimary = Color(0xFFB5D5C2),
    secondary = Muted, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EBE5), onSecondaryContainer = Color(0xFF1B2A22),
    tertiary = Color(0xFF4A6353), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7E6DB), onTertiaryContainer = Color(0xFF101F17),
    background = Paper, onBackground = Ink,
    surface = Color(0xFFFFFEFA), onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDE6), onSurfaceVariant = Color(0xFF5C665F),
    surfaceTint = Green,
    inverseSurface = Color(0xFF2C332E), inverseOnSurface = Color(0xFFF0F2ED),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFD8DDD4), outlineVariant = Color(0xFFE6EAE2),
    surfaceBright = Color(0xFFFFFEFA), surfaceDim = Color(0xFFDDDFD7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBF8),
    surfaceContainer = Color(0xFFF3F5F0),
    surfaceContainerHigh = Color(0xFFEDF0E9),
    surfaceContainerHighest = Color(0xFFE7EBE3),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5D5C2), onPrimary = Color(0xFF173127),
    primaryContainer = Color(0xFF293E32), onPrimaryContainer = Color(0xFFD9E9DD),
    inversePrimary = Green,
    secondary = Color(0xFFAAB5AD), onSecondary = Color(0xFF1B2620),
    secondaryContainer = Color(0xFF2B3B31), onSecondaryContainer = Color(0xFFDCE9E0),
    tertiary = Color(0xFFB1CBB8), onTertiary = Color(0xFF1C3327),
    tertiaryContainer = Color(0xFF34493C), onTertiaryContainer = Color(0xFFD3E8D9),
    background = Dark, onBackground = Color(0xFFE7EAE5),
    surface = Color(0xFF212823), onSurface = Color(0xFFE7EAE5),
    surfaceVariant = Color(0xFF29332D), onSurfaceVariant = Color(0xFFB9C4BC),
    surfaceTint = Color(0xFFB5D5C2),
    inverseSurface = Color(0xFFE7EAE5), inverseOnSurface = Color(0xFF232B26),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF39463E), outlineVariant = Color(0xFF2E3A33),
    surfaceBright = Color(0xFF39423C), surfaceDim = Dark,
    surfaceContainerLowest = Color(0xFF111613),
    surfaceContainerLow = Color(0xFF1C2320),
    surfaceContainer = Color(0xFF212823),
    surfaceContainerHigh = Color(0xFF2A312C),
    surfaceContainerHighest = Color(0xFF333B35),
    scrim = Color.Black,
)

@Composable
fun MeetingAtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
