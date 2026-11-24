package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import hivens.look.shape.api.Shape
import hivens.ui.press.toggleable
import hivens.ui.theme.Motion
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.theme.Status

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
     *  Null = the theme's lead colour. */
    accent: Color? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    val onTrack = accent ?: NxColor.lead()
    val offTrack = NxColor.wash(NxInk.quiet, 0.45f)
    val trackTarget = if (checked) onTrack else offTrack
    val trackColor by animateColorAsState(
        targetValue = trackTarget.copy(alpha = alpha),
        animationSpec = Motion.colorShift.of(),
        label = "nxSwitchTrack",
    )
    // The thumb is whichever ink reads on the track it is sitting in. It used to be
    // white always, which is about 2:1 on a pale accent and 1.5:1 on a light page.
    val thumbColor by animateColorAsState(
        targetValue = NxColor.on(trackTarget).copy(alpha = alpha),
        animationSpec = Motion.colorShift.of(),
        label = "nxSwitchThumb",
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
            .clip(Shape.capsule)
            .background(trackColor)
            .let { m ->
                if (onCheckedChange != null) m.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else m
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(thumbSize)
                .clip(Shape.capsule)
                .background(thumbColor),
        )
    }
}

private val trackWidth = 44.dp
private val trackHeight = 24.dp
private val thumbSize = 18.dp

@Preview
@Composable
private fun NxSwitchDarkPreview() = SwitchStates(dark = true)

@Preview
@Composable
private fun NxSwitchLightPreview() = SwitchStates(dark = false)

@Composable
private fun SwitchStates(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(280.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (enabled in listOf(true, false)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NxSwitch(checked = true, onCheckedChange = {}, enabled = enabled)
                    NxSwitch(checked = false, onCheckedChange = {}, enabled = enabled)
                    NxSwitch(checked = true, onCheckedChange = {}, enabled = enabled, accent = NxColor.status(Status.Error))
                    NxSwitch(checked = true, onCheckedChange = null, enabled = enabled)
                }
            }
        }
    }
}
