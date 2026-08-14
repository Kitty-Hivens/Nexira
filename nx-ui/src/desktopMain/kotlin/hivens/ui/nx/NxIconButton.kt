package hivens.ui.nx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * Icon-only clickable with a shape-correct CIRCULAR state layer: the hover/press
 * tint is a round disc matching the round hit target, not a mismatched rectangle
 * ripple (Rule 5/D28). The first consumer of [ShapedStateLayer]'s Shape overload --
 * it hands the layer the host's actual [CircleShape].
 */
@Composable
fun NxIconButton(
    icon: IconKey,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = NxTheme.colors.textSecondary,
    enabled: Boolean = true,
    iconSize: Dp = 18.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val shownTint = if (enabled) tint else tint.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication        = ShapedStateLayer(CircleShape, shownTint),
                enabled           = enabled,
                onClick           = onClick,
            )
            .padding(Spacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon, contentDescription, tint = shownTint, size = iconSize)
    }
}
