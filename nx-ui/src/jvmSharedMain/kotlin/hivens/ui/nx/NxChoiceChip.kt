package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.OnFill
import hivens.ui.theme.Spacing

/**
 * A small selectable chip (e.g. regex / bold). Selected = a wash of the theme's lead
 * colour with the label in it. The hover/press state layer is shape-correct at the
 * button corner, so the feedback matches the pill instead of a default Material ripple.
 * [enabled] greys the chip (an unavailable option stays visible, per the
 * capability-surfacing rule) and drops its click handling.
 */
@Composable
fun NxChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val alpha = if (enabled) 1f else 0.4f
    val bg = if (selected) NxColor.wash(NxColor.lead(), 0.18f * alpha)
             else NxColor.wash(NxInk.quiet, 0.08f * alpha)
    val interaction = remember { MutableInteractionSource() }
    OnFill(bg) {
        val fg = (if (selected) NxColor.lead() else NxInk.quiet).copy(alpha = alpha)
        NxSteadyText(
            text       = label,
            style      = MaterialTheme.typography.labelSmall,
            color      = fg,
            // Bold says "picked", and the chip used to widen as it said so: the row
            // of chips shifted under the cursor that had just picked one.
            weight     = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = modifier
                .clip(shape)
                .background(bg)
                .clickable(
                    interactionSource = interaction,
                    indication        = ShapedStateLayer(shape, fg),
                    enabled           = enabled,
                    onClick           = onToggle,
                )
                .padding(horizontal = Spacing.s10, vertical = Spacing.s6),
        )
    }
}

@Preview
@Composable
private fun NxChoiceChipDarkPreview() = ChoiceChipStates(dark = true)

@Preview
@Composable
private fun NxChoiceChipLightPreview() = ChoiceChipStates(dark = false)

@Composable
private fun ChoiceChipStates(dark: Boolean) {
    NxTheme(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxChoiceChip("Regex", selected = true, onToggle = {})
            NxChoiceChip("Bold", selected = false, onToggle = {})
            NxChoiceChip("Locked on", selected = true, enabled = false, onToggle = {})
            NxChoiceChip("Locked off", selected = false, enabled = false, onToggle = {})
        }
    }
}
