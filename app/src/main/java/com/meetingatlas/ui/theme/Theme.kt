package com.meetingatlas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF1B241F)
private val Muted = Color(0xFF55635C)
private val Green = Color(0xFF2C5A46)
private val Paper = Color(0xFFF6F5EF)
private val Dark = Color(0xFF121815)

/** Brass accent - the "atlas" highlight used for accents, focus and call-outs. */
private val Brass = Color(0xFFB4813A)
private val BrassLight = Color(0xFFE0B978)

/**
 * Complete Material 3 scheme. Filling the extended surface roles keeps NavigationBar, dialogs,
 * bottom sheets and cards in the green brand palette instead of the lavender library baseline.
 */
private val LightColors = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6DA), onPrimaryContainer = Color(0xFF0B291D),
    inversePrimary = Color(0xFFB5D5C2),
    secondary = Muted, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EAE3), onSecondaryContainer = Color(0xFF17251E),
    tertiary = Brass, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E4C6), onTertiaryContainer = Color(0xFF3A2708),
    background = Paper, onBackground = Ink,
    surface = Color(0xFFFFFDF8), onSurface = Ink,
    surfaceVariant = Color(0xFFE7ECE5), onSurfaceVariant = Color(0xFF566259),
    surfaceTint = Green,
    inverseSurface = Color(0xFF28312C), inverseOnSurface = Color(0xFFEFF2EC),
    error = Color(0xFFA8342A), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFCBD3C8), outlineVariant = Color(0xFFE2E8DF),
    surfaceBright = Color(0xFFFFFDF8), surfaceDim = Color(0xFFD9DED4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBF6),
    surfaceContainer = Color(0xFFF3F5EE),
    surfaceContainerHigh = Color(0xFFECEFE7),
    surfaceContainerHighest = Color(0xFFE5EAE0),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8CDB6), onPrimary = Color(0xFF113023),
    primaryContainer = Color(0xFF25382D), onPrimaryContainer = Color(0xFFD4E8DA),
    inversePrimary = Green,
    secondary = Color(0xFFA6B3AA), onSecondary = Color(0xFF17221C),
    secondaryContainer = Color(0xFF28352C), onSecondaryContainer = Color(0xFFD8E4DA),
    tertiary = BrassLight, onTertiary = Color(0xFF3A2708),
    tertiaryContainer = Color(0xFF4A3413), onTertiaryContainer = Color(0xFFF6E4C6),
    background = Dark, onBackground = Color(0xFFE4E9E3),
    surface = Color(0xFF1E2621), onSurface = Color(0xFFE4E9E3),
    surfaceVariant = Color(0xFF28322C), onSurfaceVariant = Color(0xFFB5C0B8),
    surfaceTint = Color(0xFFA8CDB6),
    inverseSurface = Color(0xFFE4E9E3), inverseOnSurface = Color(0xFF1E2621),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF3A463E), outlineVariant = Color(0xFF2C3730),
    surfaceBright = Color(0xFF39423C), surfaceDim = Dark,
    surfaceContainerLowest = Color(0xFF0D1210),
    surfaceContainerLow = Color(0xFF1A211D),
    surfaceContainer = Color(0xFF1E2621),
    surfaceContainerHigh = Color(0xFF28302B),
    surfaceContainerHighest = Color(0xFF333B36),
    scrim = Color(0xFF000000),
)

/**
 * Brand-specific colours that sit outside the Material roles.
 *
 * Kept deliberately small: the hero gradient, the recording "live" accent and the
 * translucent tints used for chips. Everything else should use [MaterialTheme.colorScheme].
 */
@Immutable
data class AtlasColors(
    val heroStart: Color,
    val heroEnd: Color,
    val heroOn: Color,
    val live: Color,
    val liveContainer: Color,
    val onLiveContainer: Color,
    val accent: Color,
    val accentSoft: Color,
    val cardBorder: Color,
    val shadow: Color,
    val quoteRule: Color,
) {
    /** Vertical wash used behind the home hero and the recording screen. */
    val heroBrush: Brush
        get() = Brush.verticalGradient(listOf(heroStart, heroEnd))

    /** Diagonal wash used for accent surfaces. */
    val accentBrush: Brush
        get() = Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.78f)))
}

private val LightAtlasColors = AtlasColors(
    heroStart = Color(0xFF1F4A39),
    heroEnd = Color(0xFF346B52),
    heroOn = Color(0xFFF3F7F2),
    live = Color(0xFFB3261E),
    liveContainer = Color(0xFFFBE0DE),
    onLiveContainer = Color(0xFF5B1512),
    accent = Brass,
    accentSoft = Color(0xFFF6E4C6),
    cardBorder = Color(0xFFE2E8DF),
    shadow = Color(0x14203A2C),
    quoteRule = Brass,
)

private val DarkAtlasColors = AtlasColors(
    heroStart = Color(0xFF16261E),
    heroEnd = Color(0xFF24463A),
    heroOn = Color(0xFFE7EFE8),
    live = Color(0xFFF2B8B5),
    liveContainer = Color(0xFF5C1B18),
    onLiveContainer = Color(0xFFF9DEDC),
    accent = BrassLight,
    accentSoft = Color(0xFF3C2C12),
    cardBorder = Color(0xFF2C3730),
    shadow = Color(0x33000000),
    quoteRule = BrassLight,
)

/** Corner radius scale. Cards use 20dp, sheets use 28dp, controls 12-14dp. */
val AtlasShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

val LocalAtlasColors = staticCompositionLocalOf { LightAtlasColors }

/** Shorthand for the brand palette: `AtlasTheme.colors.heroStart`. */
object AtlasTheme {
    val colors: AtlasColors
        @Composable get() = LocalAtlasColors.current
}

@Composable
fun MeetingAtlasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val atlasColors = if (darkTheme) DarkAtlasColors else LightAtlasColors

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAtlasColors provides atlasColors,
        LocalAtlasTextStyles provides AtlasTextStyles(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AtlasTypography,
            shapes = AtlasShapes,
            content = content,
        )
    }
}
