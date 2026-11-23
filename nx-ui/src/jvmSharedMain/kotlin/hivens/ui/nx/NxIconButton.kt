package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.look.shape.api.Shape
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.theme.NeutralTheme
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackTarget
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.theme.Status

/**
 * Icon-only clickable with a shape-correct state layer: the hover/press tint takes
 * the round hit target's own outline, the look's capsule, not a mismatched rectangle
 * ripple (Rule 5/D28). It hands the feedback axis the host's actual shape, and the
 * look answers how a control there is hovered and pressed.
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
    tint: Color = NxInk.quiet,
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
    val shape = Shape.capsule
    NxTooltip(
        behaviour = NxTooltipBehaviour.LabelCentred,
        text     = contentDescription.orEmpty(),
        enabled  = tooltip && !contentDescription.isNullOrBlank(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .layerTrigger()
                .clickable(
                    interactionSource = interaction,
                    indication        = Feedback.indication(FeedbackTarget.Control, shape, shownTint),
                    enabled           = enabled,
                    onClick           = onClick,
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(icon, contentDescription, tint = shownTint, fill = fill, weight = weight, size = iconSize)
        }
    }
}

@Preview
@Composable
private fun NxIconButtonDarkPreview() = IconButtonStates(dark = true)

@Preview
@Composable
private fun NxIconButtonLightPreview() = IconButtonStates(dark = false)

@Composable
private fun IconButtonStates(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            NxIconButton(NxIcon.Settings, "Settings", onClick = {})
            NxIconButton(NxIcon.Refresh, "Refresh", onClick = {}, tint = NxInk.main)
            NxIconButton(NxIcon.Favorite, "Favourite", onClick = {}, tint = NxColor.lead(), fill = 1f)
            NxIconButton(NxIcon.Delete, "Delete", onClick = {}, tint = NxColor.status(Status.Error))
            NxIconButton(NxIcon.Upload, "Upload", onClick = {}, enabled = false)
            NxIconButton(NxIcon.Pause, "Pause", onClick = {}, tint = NxInk.main, iconSize = 28.dp, fill = 1f, weight = 600)
            NxIconButton(NxIcon.Close, null, onClick = {}, iconSize = 14.dp)
        }
    }
}
