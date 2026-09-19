package hivens.ui.nx

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * A generic in-plane settings row: optional [icon] + [title] (+ [subtitle]) on the
 * left, a [trailing] slot on the right, optionally clickable. It draws no surface
 * of its own and is meant to sit INSIDE an [NxSection] plane next to [NxToggle] rows
 * (for a standalone shortcut on its own plane use [NxNavRow] instead).
 *
 * When [onClick] is set the whole row is the hit target. Its hover/press highlight is
 * the shared soft NEUTRAL overlay ([softHoverAlpha]) and bleeds out to the section
 * plane's edges ([edgeBleed] matches the NxSection inset) so it reads as a full-width
 * list row.
 *
 * Two parameters make the same row serve a narrow panel as well as a settings page,
 * which is what a second copy of it was being written for.
 *
 * [compact] is the panel form, for the same reason [NxSlider] has one: a 320dp panel
 * carries its own heading, and a row set in the page's body size reads as the loudest
 * thing on it. It drops the label a step, tightens the band and shrinks the icon.
 *
 * [labelWidth] pins the label column so a column of unlike controls lines up. A form
 * wants that and a list does not, which is why null (the default) keeps the label
 * taking what it needs and the trailing slot sitting against the far edge. Without
 * it, the panel this was written for had a switch aligned one way, four fields
 * another and two sliders a third, because each row had picked its own answer.
 */
@Composable
fun NxRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: IconKey? = null,
    iconTint: Color = NxTheme.colors.textSecondary,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    edgeBleed: Dp = 16.dp,
    compact: Boolean = false,
    labelWidth: Dp? = null,
    trailing: @Composable () -> Unit = {},
) {
    val rowModifier = if (onClick != null) {
        val shape = MaterialTheme.shapes.medium
        val interaction = remember { MutableInteractionSource() }
        val alpha = softHoverAlpha(interaction)
        Modifier
            .bleedHorizontally(edgeBleed)
            .fillMaxWidth()
            .clip(shape)
            .background(NxTheme.colors.textPrimary.copy(alpha = alpha))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = edgeBleed, vertical = Spacing.s8)
    } else {
        Modifier.fillMaxWidth().padding(vertical = if (compact) Spacing.s4 else Spacing.s8)
    }
    Row(
        modifier              = modifier.then(rowModifier),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // A pinned column is a fixed width. An unpinned one takes what is left after
        // the trailing slot has measured, which is what makes a list row's control sit
        // against the far edge whatever the label says.
        val label = if (labelWidth != null) Modifier.width(labelWidth) else Modifier.weight(1f)
        Row(label, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Symbol(icon, null, tint = iconTint, size = if (compact) 16.dp else 22.dp)
                Spacer(Modifier.width(if (compact) Spacing.s8 else Spacing.s12))
            }
            Column {
                Text(
                    title,
                    // LocalTextStyle rather than a named role on the wide form: that is
                    // what this row has always drawn, and naming a role here would move
                    // every existing call site to prove a point about the narrow one.
                    style      = if (compact) MaterialTheme.typography.bodySmall else LocalTextStyle.current,
                    color      = if (compact) NxTheme.colors.textSecondary else NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(if (compact) Spacing.s8 else Spacing.s12))
        // The trailing slot takes the rest of the row when the label is pinned, so a
        // field or a slider fills the column it was given rather than wrapping to its
        // own content and leaving a gap the eye reads as a missing control.
        if (labelWidth != null) Box(Modifier.weight(1f)) { trailing() } else trailing()
    }
}

/**
 * The soft NEUTRAL row-highlight alpha for a clickable [interaction]: a low-opacity
 * onSurface overlay that fades in on hover-enter / out on hover-exit -- steady while
 * hovered, never pulsing, and instant when motion is off. Shared by [NxRow] (in-plane)
 * and [NxNavRow] (own plane) so a navigable row reads the same wherever it sits.
 */
@Composable
internal fun softHoverAlpha(interaction: MutableInteractionSource): Float {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val target = when {
        pressed -> 0.11f
        hovered -> 0.06f
        else    -> 0f
    }
    val alpha by animateFloatAsState(
        targetValue   = target,
        animationSpec = Motion.tap,
        label         = "softHoverAlpha",
    )
    return alpha
}

/**
 * Widens the laid-out content by [amount] on each side and shifts it left by the same,
 * while reporting the original width to the parent -- so a row's highlight bleeds to
 * the plane edges without disturbing the column layout. Content re-insets via its own
 * horizontal padding.
 */
private fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
        placeable.place(-amount.roundToPx(), 0)
    }
}
