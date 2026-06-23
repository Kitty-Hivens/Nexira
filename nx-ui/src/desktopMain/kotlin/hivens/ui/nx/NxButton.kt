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
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * The one button. Four emphasis roles (the taxonomy from #182) -- a call site
 * picks a role, not colours. The per-widget freedom the old de-facto base button
 * had (an arbitrary Material ButtonColors) moves up to the Flexible event layer.
 *
 *  - [Primary]     -- filled accent; the one obvious next action (Play, Login). One per screen.
 *  - [Secondary]   -- outlined, transparent; alternative actions (Cancel, Open folder).
 *  - [Tertiary]    -- text only, no border; low-emphasis / navigation (View on GitHub, Register).
 *  - [Destructive] -- filled error; a destructive or critical action (Delete, Force re-download).
 *
 * Sizing stays provisional ([compact] / [minHeight]) until the dimens token scale
 * (#350) lands; decorative glow lives at the call site or the effects layer (#352),
 * not in the button -- both are the separate styling track, not a button role.
 */
enum class NxButtonStyle { Primary, Secondary, Tertiary, Destructive }

@Composable
fun NxButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NxButtonStyle = NxButtonStyle.Primary,
    icon: IconKey? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    minHeight: Dp? = null,
) {
    val palette = NxTheme.colors
    val styleSpec = LocalStyle.current
    val shape = MaterialTheme.shapes.small

    val container: Color = when (style) {
        NxButtonStyle.Primary     -> palette.primary
        NxButtonStyle.Destructive -> palette.error
        NxButtonStyle.Secondary   -> Color.Transparent
        NxButtonStyle.Tertiary    -> Color.Transparent
    }
    val content: Color = when (style) {
        NxButtonStyle.Primary     -> Color.White
        NxButtonStyle.Destructive -> Color.White
        NxButtonStyle.Secondary   -> palette.textPrimary
        NxButtonStyle.Tertiary    -> palette.primary
    }
    val bordered = style == NxButtonStyle.Secondary
    val dim = if (enabled) 1f else 0.45f
    val pad = if (compact) PaddingValues(horizontal = 12.dp, vertical = 6.dp)
              else PaddingValues(horizontal = 18.dp, vertical = 10.dp)

    // Hover/press feedback through the shared ShapedStateLayer, provided as
    // LocalIndication so the clickable picks it up.
    CompositionLocalProvider(
        LocalIndication provides ShapedStateLayer(styleSpec.buttonCorner, content),
    ) {
        Row(
            modifier              = modifier
                .let { if (minHeight != null) it.heightIn(min = minHeight) else it }
                .clip(shape)
                .background(container.copy(alpha = container.alpha * dim))
                .let { if (bordered) it.border(1.dp, palette.outline.copy(alpha = dim), shape) else it }
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
