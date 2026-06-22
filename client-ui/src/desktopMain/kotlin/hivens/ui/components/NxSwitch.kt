package hivens.ui.components

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * Skin-driven toggle. Geometry (track/thumb size + corners) comes from the active
 * [hivens.ui.theme.StyleSpec.switchStyle]; colours come from the palette. Swapping the
 * style preset (Celestia pill <-> Brut square) -- or, later, a user skin -- restyles
 * every toggle in the app at once, since all of them route through here instead of a
 * raw Material `Switch` + inline `SwitchDefaults.colors`.
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
    val st = LocalStyle.current.switchStyle
    val colors = NxTheme.colors
    val alpha = if (enabled) 1f else 0.4f
    val trackColor by animateColorAsState(
        targetValue = if (checked) (accent ?: colors.primary).copy(alpha = alpha)
                      else colors.outline.copy(alpha = 0.5f * alpha),
        label = "nxSwitchTrack",
    )
    val pad = (st.trackHeight - st.thumbSize) / 2
    val thumbX by animateDpAsState(
        targetValue = if (checked) st.trackWidth - st.thumbSize - pad else pad,
        label = "nxSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(st.trackWidth, st.trackHeight)
            .clip(RoundedCornerShape(st.trackCorner))
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
                .size(st.thumbSize)
                .clip(RoundedCornerShape(st.thumbCorner))
                .background(Color.White.copy(alpha = alpha)),
        )
    }
}
