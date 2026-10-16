package com.meetingatlas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF202722)
private val Muted = Color(0xFF69746D)
private val Green = Color(0xFF315B49)
private val Paper = Color(0xFFF5F5F0)
private val Dark = Color(0xFF171C19)

@Composable
fun MeetingAtlasTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFB5D5C2), onPrimary = Color(0xFF173127),
        background = Dark, surface = Color(0xFF212823), onBackground = Color(0xFFE7EAE5),
        onSurface = Color(0xFFE7EAE5), secondary = Color(0xFFAAB5AD), outline = Color(0xFF39463E),
        surfaceVariant = Color(0xFF29332D), secondaryContainer = Color(0xFF2B3B31),
    ) else lightColorScheme(
        primary = Green, onPrimary = Color.White, background = Paper, surface = Color(0xFFFFFEFA),
        onBackground = Ink, onSurface = Ink, secondary = Muted, outline = Color(0xFFE1E4DD),
        surfaceVariant = Color(0xFFEAEEE7), secondaryContainer = Color(0xFFE6EEE7),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
