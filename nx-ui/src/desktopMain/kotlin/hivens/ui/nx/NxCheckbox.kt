package hivens.ui.nx

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * A box that is either ticked or not.
 *
 * Distinct from [NxSwitch], which says whether something is on: a switch changes
 * the world the moment it moves, a checkbox only marks a row so an action can
 * take it later. Using one for the other is why a list ends up with two rows of
 * identical-looking controls that mean different things -- which is exactly the
 * case here, where a mod's own enabled state sits inches from its selection.
 *
 * The corner follows the badge spec, the same place every other small shell in
 * the app reads it, so a square form squares this too.
 */
@Composable
fun NxCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NxTheme.colors
    val style = LocalStyle.current
    val shape = style.badgeStyle.shape()
    val mark by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(style.animationDurationMs(140)),
        label = "nxCheckboxMark",
    )

    Box(
        modifier = modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) colors.primary else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (checked) colors.primary else colors.outline,
                shape = shape,
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        // Scaled rather than swapped in, so the tick grows out of the box instead
        // of appearing on top of it.
        if (mark > 0f) {
            Symbol(
                icon = NxIcon.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                size = (12 * mark).dp,
            )
        }
    }
}
