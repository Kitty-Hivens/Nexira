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
 *
 * A glyph is not a name, so [contentDescription] is also raised as a tooltip on
 * hover. It is the same string either way -- every call site already passes the
 * control's own name from the string table -- and putting it here rather than at
 * the call sites is the difference between ten named controls and one: the library
 * had a tooltip and three places in the whole launcher used it, so every close,
 * every overflow and every nudge arrow was an unlabelled picture.
 *
 * [tooltip] false is for the caller that raises its own, with something this cannot
 * know: why the action is blocked, or a name that is already on screen beside it.
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
    tooltip: Boolean = true,
    /**
     * The glyph's own axes, passed through to [Symbol].
     *
     * A transport control wants a FILLED glyph: at this size an outline pause reads
     * as two hollow rectangles, which is to say as the digits 0 0, and no amount of
     * tint fixes that. Withholding the axes here forced a caller that needed one to
     * hand-roll a clickable and lose the round state layer and the tooltip with it.
     */
    fill: Float = 0f,
    weight: Int = 400,
) {
    val interaction = remember { MutableInteractionSource() }
    val shownTint = if (enabled) tint else tint.copy(alpha = 0.4f)
    NxTooltip(
        text     = contentDescription.orEmpty(),
        enabled  = tooltip && !contentDescription.isNullOrBlank(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
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
            Symbol(icon, contentDescription, tint = shownTint, fill = fill, weight = weight, size = iconSize)
        }
    }
}
