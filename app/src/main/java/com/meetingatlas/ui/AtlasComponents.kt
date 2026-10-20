package com.meetingatlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meetingatlas.ui.theme.AtlasTheme
import com.meetingatlas.ui.theme.LocalAtlasTextStyles

/**
 * Shared building blocks for the Meeting Atlas design language.
 *
 * Everything here reads from the theme, so the whole app re-skins from one place:
 * [com.meetingatlas.ui.theme.AtlasTypography] for type, [com.meetingatlas.ui.theme.AtlasColors]
 * for the brand palette and [com.meetingatlas.ui.theme.AtlasShapes] for radii.
 */

/**
 * The primary surface of the app: a softly raised card with a hairline border.
 * Use it for every grouped block of content.
 */
@Composable
fun AtlasCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, AtlasTheme.colors.cardBorder)
    Surface(
        modifier = modifier.shadow(6.dp, shape, clip = false, ambientColor = AtlasTheme.colors.shadow, spotColor = AtlasTheme.colors.shadow),
        color = color,
        shape = shape,
        border = border,
        content = {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(10.dp))
                }
                content()
            }
        },
    )
}

/** A card that can be tapped. The whole card is one accessibility target. */
@Composable
fun AtlasClickableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    title: String? = null,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, AtlasTheme.colors.cardBorder)
    Surface(
        modifier = modifier
            .shadow(6.dp, shape, clip = false, ambientColor = AtlasTheme.colors.shadow, spotColor = AtlasTheme.colors.shadow)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick, onClickLabel = onClickLabel)
            .semantics(mergeDescendants = true) {},
        color = color,
        shape = shape,
        border = border,
        content = {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(10.dp))
                }
                content()
            }
        },
    )
}

/**
 * Small uppercase label used above sections. Announces itself as a heading so a screen
 * reader user can jump between sections.
 */
@Composable
fun AtlasEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = LocalAtlasTextStyles.current.eyebrow,
        color = color,
    )
}

/** A section heading with an optional trailing action. */
@Composable
fun AtlasSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        trailing?.invoke(this)
    }
}

/** A rounded status pill. */
@Composable
fun AtlasPill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(color = container, shape = CircleShape, modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * A pull quote: an italic serif line with a brass rule beside it.
 * Used for transcript evidence and meeting summaries.
 */
@Composable
fun AtlasQuote(
    text: String,
    modifier: Modifier = Modifier,
    attribution: String? = null,
) {
    Row(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .height(if (attribution == null) 34.dp else 52.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AtlasTheme.colors.quoteRule),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = LocalAtlasTextStyles.current.quote, color = MaterialTheme.colorScheme.onSurface)
            attribution?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/**
 * The hero panel at the top of the home screen: brand mark over a deep green wash.
 */
@Composable
fun AtlasHero(
    modifier: Modifier = Modifier,
    eyebrow: String,
    headline: String,
    body: String,
    logo: (@Composable () -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        shape = RoundedCornerShape(26.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(AtlasTheme.colors.heroStart, AtlasTheme.colors.heroEnd)))
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    logo?.let {
                        it()
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            eyebrow.uppercase(),
                            style = LocalAtlasTextStyles.current.wordmarkSub,
                            color = AtlasTheme.colors.heroOn.copy(alpha = 0.74f),
                        )
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    headline,
                    style = LocalAtlasTextStyles.current.wordmark,
                    color = AtlasTheme.colors.heroOn,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AtlasTheme.colors.heroOn.copy(alpha = 0.82f),
                )
            }
        }
    }
}

/** Decorative "AI" marker used on generated-content cards. */
@Composable
fun AtlasGeneratedBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "Generated",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** A slim progress rail used under section headings. */
@Composable
fun AtlasRule(modifier: Modifier = Modifier, color: Color = AtlasTheme.colors.cardBorder, height: Dp = 1.dp) {
    Box(modifier.fillMaxWidth().height(height).background(color))
}

/** A key/value row used in settings and summary panels. */
@Composable
fun AtlasMetaRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** A subtle dashed-look outline container for empty states. */
@Composable
fun AtlasEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, AtlasTheme.colors.cardBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        action?.let {
            Spacer(Modifier.height(18.dp))
            it()
        }
    }
}

/** A small labelled metric, used in the recording screen. */
@Composable
fun AtlasMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/** Horizontal row of equal-width items with a gap. */
@Composable
fun AtlasRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), content = content)
}
