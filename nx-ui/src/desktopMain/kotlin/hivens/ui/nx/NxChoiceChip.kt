package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * A small selectable chip (e.g. regex / bold). Selected = an accent wash + accent
 * label; the hover/press state layer is shape-correct at the active style's button
 * corner, so the feedback matches the pill instead of a default Material ripple.
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
    val shape = RoundedCornerShape(LocalStyle.current.buttonCorner)
    val alpha = if (enabled) 1f else 0.4f
    val fg = (if (selected) NxTheme.colors.primary else NxTheme.colors.textSecondary).copy(alpha = alpha)
    val bg = if (selected) NxTheme.colors.primary.copy(alpha = 0.18f * alpha)
             else NxTheme.colors.surface.copy(alpha = alpha)
    val interaction = remember { MutableInteractionSource() }
    Text(
        text       = label,
        style      = MaterialTheme.typography.labelSmall,
        color      = fg,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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
