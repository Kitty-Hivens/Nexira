package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * Button visual variants built on the Material-3 tonal palette, so the UI reads as
 * more than "primary fill or glass":
 *
 *  - [Filled]  -- solid primary accent; the standard call-to-action.
 *  - [Tonal]   -- muted `secondaryContainer` fill; a SOLID (not glass) secondary
 *                 action -- the replacement for the ad-hoc `glassSurfaceAlpha`
 *                 buttons that made everything translucent.
 *  - [Ghost]   -- opaque `surfaceContainerHigh` chip; low-emphasis, still solid.
 *  - [Outline] -- bordered, transparent fill; for de-emphasised edges.
 */
enum class NxButtonStyle { Filled, Tonal, Ghost, Outline }

@Composable
fun NxButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NxButtonStyle = NxButtonStyle.Filled,
    icon: IconKey? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val palette = NxTheme.colors
    val shape = MaterialTheme.shapes.small

    val container: Color = when (style) {
        NxButtonStyle.Filled  -> palette.primary
        NxButtonStyle.Tonal   -> palette.secondaryContainer
        NxButtonStyle.Ghost   -> palette.surfaceContainerHigh
        NxButtonStyle.Outline -> Color.Transparent
    }
    val content: Color = when (style) {
        NxButtonStyle.Filled  -> Color.White
        NxButtonStyle.Tonal   -> palette.onSecondaryContainer
        NxButtonStyle.Ghost   -> palette.textPrimary
        NxButtonStyle.Outline -> palette.textPrimary
    }
    val dim = if (enabled) 1f else 0.45f
    val pad = if (compact) PaddingValues(horizontal = 12.dp, vertical = 6.dp)
              else PaddingValues(horizontal = 18.dp, vertical = 10.dp)

    // Hover/press feedback through the shared ShapedStateLayer -- the same
    // shape-correct state layer the easter-egg button uses, now owned by the
    // base components. Provided as LocalIndication so the clickable picks it up.
    CompositionLocalProvider(
        LocalIndication provides ShapedStateLayer(LocalStyle.current.buttonCorner, content),
    ) {
        Row(
            modifier              = modifier
                .clip(shape)
                .background(container.copy(alpha = container.alpha * dim))
                .let { if (style == NxButtonStyle.Outline) it.border(1.dp, palette.outline.copy(alpha = dim), shape) else it }
                .clickable(enabled = enabled, onClick = onClick)
                .padding(pad),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            if (icon != null) {
                Symbol(icon, contentDescription = null, tint = content.copy(alpha = dim), size = if (compact) 16.dp else 18.dp)
            }
            Text(
                text       = label,
                color      = content.copy(alpha = dim),
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
