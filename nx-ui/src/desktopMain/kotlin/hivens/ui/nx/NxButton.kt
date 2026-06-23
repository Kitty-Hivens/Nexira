package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.effects.pulsatingGlow
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * The one button. A finite set of semantic styles replaces the old free-for-all,
 * where the de-facto base button took an arbitrary Material `ButtonColors` and
 * every call site invented its own look. Call sites pick a style, not colours --
 * the per-widget freedom that used to live here moves up to the Flexible event
 * layer, where it belongs.
 *
 *  - [Filled]  -- primary accent fill; the standard call-to-action.
 *  - [Danger]  -- error fill; a destructive or critical action.
 *  - [Glass]   -- translucent surface; the secondary action over the app's glass.
 *  - [Tonal]   -- opaque `secondaryContainer`; a solid secondary action.
 *  - [Outline] -- bordered, transparent; a de-emphasised edge action.
 *  - [Link]    -- transparent, accent text, no border; an inline link action.
 *  - [Ghost]   -- transparent, muted text; a tertiary, low-emphasis action.
 */
enum class NxButtonStyle { Filled, Danger, Glass, Tonal, Outline, Link, Ghost }

@Composable
fun NxButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NxButtonStyle = NxButtonStyle.Filled,
    icon: IconKey? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    glowing: Boolean = false,
    minHeight: Dp? = null,
) {
    val palette = NxTheme.colors
    val styleSpec = LocalStyle.current
    val shape = MaterialTheme.shapes.small
    // Read unconditionally so the Glass branch reuses it without a conditional
    // composable call. Honours the user's glass-intensity knob like every surface.
    val glass = glassSurfaceAlpha(0.52f)

    val container: Color = when (style) {
        NxButtonStyle.Filled  -> palette.primary
        NxButtonStyle.Danger  -> palette.error
        NxButtonStyle.Glass   -> glass
        NxButtonStyle.Tonal   -> palette.secondaryContainer
        NxButtonStyle.Outline -> Color.Transparent
        NxButtonStyle.Link    -> Color.Transparent
        NxButtonStyle.Ghost   -> Color.Transparent
    }
    val content: Color = when (style) {
        NxButtonStyle.Filled  -> Color.White
        NxButtonStyle.Danger  -> Color.White
        NxButtonStyle.Glass   -> palette.textPrimary
        NxButtonStyle.Tonal   -> palette.onSecondaryContainer
        NxButtonStyle.Outline -> palette.textPrimary
        NxButtonStyle.Link    -> palette.primary
        NxButtonStyle.Ghost   -> palette.textSecondary
    }
    val dim = if (enabled) 1f else 0.45f
    val pad = if (compact) PaddingValues(horizontal = 12.dp, vertical = 6.dp)
              else PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    val showGlow = glowing && styleSpec.softGlowEnabled

    // Hover/press feedback through the shared ShapedStateLayer, provided as
    // LocalIndication so the clickable picks it up.
    CompositionLocalProvider(
        LocalIndication provides ShapedStateLayer(styleSpec.buttonCorner, content),
    ) {
        Row(
            modifier              = modifier
                .let { if (minHeight != null) it.heightIn(min = minHeight) else it }
                .let { if (showGlow) it.pulsatingGlow(palette.primary, cornerRadius = styleSpec.buttonCorner) else it }
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
