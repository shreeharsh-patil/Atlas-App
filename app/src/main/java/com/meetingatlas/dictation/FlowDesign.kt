package com.meetingatlas.dictation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetingatlas.R

// Sampled from the public Android Play artwork. Night surfaces are Atlas' extension.
internal const val FlowLavenderArgb = 0xFFF0D7FF.toInt()
internal const val FlowInkArgb = 0xFF1A1A1A.toInt()
internal const val FlowCreamArgb = 0xFFFFFFEB.toInt()
private val paper = Color(0xFFFAF9F6)
private val ink = Color(FlowInkArgb)
private val night = Color(0xFF19181B)
private val lavender = Color(FlowLavenderArgb)
private val sans = FontFamily(
    Font(R.font.manrope_regular),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)
private val serif = FontFamily(Font(R.font.lora_regular), Font(R.font.lora_semibold, FontWeight.SemiBold))
private val light = lightColorScheme(
    background = paper, onBackground = ink, surface = Color.White, onSurface = ink,
    surfaceVariant = Color(0xFFF2F0ED), onSurfaceVariant = Color(0xFF58545B),
    surfaceContainer = Color(0xFFF4F2EF), surfaceContainerHigh = Color(0xFFF0EEEB),
    primary = lavender, onPrimary = ink, primaryContainer = lavender, onPrimaryContainer = ink,
    secondary = Color(0xFF62586B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4D0), onSecondaryContainer = ink,
    tertiaryContainer = Color(FlowCreamArgb), onTertiaryContainer = ink,
    outline = Color(0xFF726D75), outlineVariant = Color(0xFFDDDBDE),
    error = Color(0xFFA1264A), errorContainer = Color(0xFFFDEAF0), onErrorContainer = Color(0xFF721832),
)
private val dark = darkColorScheme(
    background = night, onBackground = Color(0xFFF3EFF4), surface = Color(0xFF231F25), onSurface = Color(0xFFF3EFF4),
    surfaceVariant = Color(0xFF302A33), onSurfaceVariant = Color(0xFFC6BBCB),
    surfaceContainer = Color(0xFF27232A), surfaceContainerHigh = Color(0xFF2C2730),
    primary = lavender, onPrimary = ink, primaryContainer = Color(0xFF493751), onPrimaryContainer = lavender,
    secondary = Color(0xFFD7BDE8), onSecondary = Color(0xFF24192B),
    secondaryContainer = Color(0xFF3D4030), onSecondaryContainer = Color(0xFFE5E7D4),
    tertiaryContainer = Color(0xFF343126), onTertiaryContainer = Color(0xFFF7EBC8),
    outline = Color(0xFFADA1B1), outlineVariant = Color(0xFF4D454E),
    error = Color(0xFFFFABC1), errorContainer = Color(0xFF492332), onErrorContainer = Color(0xFFFFCCDA),
)
private fun face(size: Int, line: Int, family: FontFamily = sans, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
private val type = Typography(
    displaySmall = face(38, 43, serif).copy(letterSpacing = (-1.6).sp),
    headlineLarge = face(32, 38, serif).copy(letterSpacing = (-1.1).sp),
    headlineMedium = face(27, 33, serif).copy(letterSpacing = (-.6).sp),
    headlineSmall = face(23, 29, serif),
    titleLarge = face(20, 27, sans, FontWeight.SemiBold), titleMedium = face(16, 22, sans, FontWeight.Bold),
    titleSmall = face(14, 20, sans, FontWeight.SemiBold),
    bodyLarge = face(16, 24), bodyMedium = face(14, 21), bodySmall = face(12, 18),
    labelLarge = face(14, 20, sans, FontWeight.Bold), labelMedium = face(12, 17, sans, FontWeight.SemiBold),
    labelSmall = face(11, 16, sans, FontWeight.SemiBold),
)

@Composable internal fun FlowTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) dark else light
    MaterialTheme(colorScheme = scheme, typography = type,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(20.dp))) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

@Composable internal fun flowBackdrop(): Color = if (isSystemInDarkTheme()) night else paper

@Composable internal fun FlowTitle(text: String, large: Boolean = false) {
    Text(text, style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
        modifier = Modifier.semantics { heading() })
}
@Composable internal fun FlowMuted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

@Composable internal fun FlowSection(text: String) =
    Text(text.uppercase(), modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.15.sp))

@Composable internal fun FlowButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = if (enabled) BorderStroke(1.dp, Color(FlowInkArgb)) else null,
        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 12.dp)) { Text(label) }
}

@Composable internal fun FlowOutline(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text(label) }
}

@Composable internal fun FlowTextButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface), content = content)
}

@Composable internal fun FlowDialog(
    onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismissRequest, title = title, text = text,
        confirmButton = confirmButton, dismissButton = dismissButton, containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp))
}

@Composable internal fun FlowCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable internal fun FlowRow(
    title: String, detail: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val contents: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(21.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (trailing != null) trailing()
            else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick != null) Surface(onClick = onClick, color = Color.Transparent, content = contents)
    else contents()
}

@Composable internal fun FlowRows(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(content = content)
    }
}
@Composable internal fun FlowLine() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
@Composable internal fun FlowField(
    value: String, change: (String) -> Unit, label: String, minLines: Int = 1,
    transformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = minLines == 1, minLines = minLines, maxLines = if (minLines == 1) 1 else maxOf(4, minLines),
        shape = RoundedCornerShape(8.dp), visualTransformation = transformation, leadingIcon = leadingIcon,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.onSurface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = MaterialTheme.colorScheme.onSurface, focusedLabelColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface))
}

@Composable internal fun FlowOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(20.dp))
        }
    }
}

@Composable internal fun FlowEmpty(icon: ImageVector, title: String, body: String, action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable internal fun FlowWave(modifier: Modifier, color: Color = Color(FlowInkArgb), level: Float? = null) {
    Canvas(modifier) {
        val profiles = listOf(.32f, .53f, .83f, .57f, .95f, .45f, .25f)
        val gap = size.width / (profiles.size * 2 - 1)
        profiles.forEachIndexed { index, ratio ->
            val h = (if (level == null) ratio else .12f + ratio * .88f * level.coerceIn(0f, 1f)) * size.height
            drawRoundRect(color, Offset(index * gap * 2, (size.height - h) / 2), Size(gap, h), CornerRadius(gap / 2))
        }
    }
}
