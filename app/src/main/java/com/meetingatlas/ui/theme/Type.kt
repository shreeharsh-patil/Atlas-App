package com.meetingatlas.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.meetingatlas.R

/**
 * Type system for Meeting Atlas.
 *
 * Three families, each with a clear job:
 *
 *  - [Lobster] - the brand voice. Script display face used for the wordmark and
 *    screen titles only. Never used for body copy: a script face at reading size
 *    is slow to scan.
 *  - [LobsterTwo] - the same family with a *true* italic cut, used for decorative
 *    accents and pull quotes where an oblique look is intentional.
 *  - [Lora] - the reading face. A high-x-height serif with real italics, used for
 *    everything the user actually reads: meeting titles, note prose, transcript.
 *  - [Manrope] - the interface face. A geometric sans used for controls, chips,
 *    counters and metadata, where a serif would be too soft.
 *
 * All four families are SIL Open Font License 1.1; see `third_party_fonts/`.
 */

val Lobster = FontFamily(
    Font(R.font.lobster_regular, weight = FontWeight.Normal),
    Font(R.font.lobster_regular, weight = FontWeight.Bold),
    Font(R.font.lobster_regular, weight = FontWeight.SemiBold),
)

val LobsterTwo = FontFamily(
    Font(R.font.lobster_two_regular, weight = FontWeight.Normal),
    Font(R.font.lobster_two_bold, weight = FontWeight.Bold),
    Font(R.font.lobster_two_regular, weight = FontWeight.SemiBold),
    Font(R.font.lobster_two_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.lobster_two_bold, weight = FontWeight.Bold, style = FontStyle.Italic),
)

val Lora = FontFamily(
    Font(R.font.lora_regular, weight = FontWeight.Normal),
    Font(R.font.lora_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.lora_semibold, weight = FontWeight.SemiBold),
    Font(R.font.lora_semibold_italic, weight = FontWeight.SemiBold, style = FontStyle.Italic),
)

val Manrope = FontFamily(
    Font(R.font.manrope_regular, weight = FontWeight.Normal),
    Font(R.font.manrope_medium, weight = FontWeight.Medium),
    Font(R.font.manrope_semibold, weight = FontWeight.SemiBold),
    Font(R.font.manrope_bold, weight = FontWeight.Bold),
    Font(R.font.manrope_extrabold, weight = FontWeight.ExtraBold),
)

/** Script face for the wordmark and screen titles. */
private fun display(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = Lobster,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** True-italic script face for accents and pull quotes. */
private fun displayItalic(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = LobsterTwo,
    fontWeight = weight,
    fontStyle = FontStyle.Italic,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** Reading face. */
private fun serif(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = Lora,
    fontWeight = weight,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** Interface face. */
private fun sans(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Medium,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The Material 3 type scale for Meeting Atlas.
 *
 * Display slots use [Lobster]; title and body slots use [Lora] (upright for titles,
 * italic reserved for prose accents via [AtlasTextStyles]); label slots use [Manrope].
 */
val AtlasTypography = Typography(
    // ---- Display: the brand voice -------------------------------------------
    displayLarge = display(58, 66),
    displayMedium = display(46, 54),
    displaySmall = display(38, 46),

    // ---- Headline: screen titles, still Lobster ------------------------------
    headlineLarge = display(38, 46),
    headlineMedium = display(32, 40),
    headlineSmall = display(27, 34),

    // ---- Title: the serif reading face --------------------------------------
    titleLarge = serif(22, 30, FontWeight.SemiBold),
    titleMedium = serif(18, 26, FontWeight.SemiBold),
    titleSmall = serif(15, 22, FontWeight.SemiBold),

    // ---- Body: the serif reading face ---------------------------------------
    bodyLarge = serif(17, 27),
    bodyMedium = serif(15, 24),
    bodySmall = serif(13, 20),

    // ---- Label: the interface face ------------------------------------------
    labelLarge = sans(15, 20, FontWeight.SemiBold, letterSpacing = 0.1),
    labelMedium = sans(13, 18, FontWeight.SemiBold, letterSpacing = 0.4),
    labelSmall = sans(11, 16, FontWeight.SemiBold, letterSpacing = 0.6),
)

/**
 * Styles that sit outside the Material scale but are used across the app.
 * Exposed through [LocalAtlasTextStyles] so screens can stay declarative.
 */
data class AtlasTextStyles(
    val wordmark: TextStyle = display(26, 30, letterSpacing = 0.6),
    val wordmarkSub: TextStyle = sans(12, 16, FontWeight.SemiBold, letterSpacing = 2.2),
    val eyebrow: TextStyle = sans(11, 15, FontWeight.Bold, letterSpacing = 1.8),
    val hero: TextStyle = displayItalic(30, 38),
    val quote: TextStyle = serif(16, 26, FontWeight.Normal, italic = true),
    val quoteSmall: TextStyle = serif(14, 22, FontWeight.Normal, italic = true),
    val timer: TextStyle = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1).sp,
    ),
    val timerSmall: TextStyle = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val meta: TextStyle = sans(12, 17, FontWeight.Medium, letterSpacing = 0.2),
    val script: TextStyle = displayItalic(22, 28),
)

/** Non-Material text roles, provided to the tree by [MeetingAtlasTheme]. */
val LocalAtlasTextStyles = staticCompositionLocalOf { AtlasTextStyles() }
