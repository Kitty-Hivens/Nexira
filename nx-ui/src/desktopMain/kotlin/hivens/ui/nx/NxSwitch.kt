package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Motion

/**
 * The toggle primitive. Its track and thumb are sized here; colours come from
 * the palette.
 *
 * Every toggle in the app routes through here rather than a raw Material `Switch`
 * with inline `SwitchDefaults.colors`, so the shell has one place to be changed.
 *
 * Drop-in for the call sites that need checked + onChange (+ optional enabled). A null
 * [onCheckedChange] renders a read-only switch (no interaction).
 */
@Composable
fun NxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Checked-track colour override for semantic toggles (e.g. a red offline switch).
     *  Null = the palette accent. */
    accent: Color? = null,
) {
    val colors = NxTheme.colors
    val alpha = if (enabled) 1f else 0.4f
    val trackColor by animateColorAsState(
        targetValue = if (checked) (accent ?: colors.primary).copy(alpha = alpha)
                      else colors.outline.copy(alpha = 0.5f * alpha),
        animationSpec = Motion.colorShift.of(),
        label = "nxSwitchTrack",
    )
    val pad = (trackHeight - thumbSize) / 2
    val thumbX by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - pad else pad,
        animationSpec = Motion.tap.of(),
        label = "nxSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(trackCorner))
            .background(trackColor)
            .let { m ->
                if (onCheckedChange != null) m.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else m
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbX)
                .size(thumbSize)
                .clip(RoundedCornerShape(thumbCorner))
                .background(Color.White.copy(alpha = alpha)),
        )
    }
}

private val trackWidth = 44.dp
private val trackHeight = 24.dp
private val thumbSize = 18.dp
private val trackCorner = 12.dp
private val thumbCorner = 9.dp
