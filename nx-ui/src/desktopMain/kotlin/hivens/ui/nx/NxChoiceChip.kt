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
import androidx.compose.ui.unit.dp
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * A small selectable chip (e.g. regex / bold). Selected = an accent wash + accent
 * label; the hover/press state layer is shape-correct at the active style's button
 * corner, so the feedback matches the pill instead of a default Material ripple.
 */
@Composable
fun NxChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(LocalStyle.current.buttonCorner)
    val fg = if (selected) NxTheme.colors.primary else NxTheme.colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Text(
        text       = label,
        style      = MaterialTheme.typography.labelSmall,
        color      = fg,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier   = modifier
            .clip(shape)
            .background(if (selected) NxTheme.colors.primary.copy(alpha = 0.18f) else NxTheme.colors.surface)
            .clickable(
                interactionSource = interaction,
                indication        = ShapedStateLayer(shape, fg),
                onClick           = onToggle,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
