package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme

/**
 * One state of an [NxCycleToggle]: the glyph that stands for it and the name it
 * answers with when asked.
 */
@androidx.compose.runtime.Immutable
data class NxCycleState(val icon: IconKey, val label: String)

/**
 * An exclusive choice of several states shown as one control that advances on
 * click.
 *
 * This is what a player's repeat and shuffle are, and it is deliberately not the
 * shape a settings page uses. A row of [NxChoiceChip]s carries its options as
 * words, which is right when the reader is deciding between them and has the
 * width to read them; a transport row has neither. Here the options are a closed
 * ring the user steps through, only the current one needs to be on screen, and
 * the glyph carries it.
 *
 * The off state is the one the ring starts from and the only one drawn muted:
 * every other state gets the accent plus a soft wash behind it, so "this is doing
 * something" is legible without a second element next to it. That is also why the
 * control does not need a label of its own.
 *
 * The name of the current state is not written anywhere on screen, so it is on
 * the tooltip: a ring the user steps through blind is a ring they stop trusting
 * after the second click.
 */
@Composable
fun NxCycleToggle(
    states: List<NxCycleState>,
    index: Int,
    onCycle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    offIndex: Int = 0,
    size: Dp = 32.dp,
    glyphSize: Dp = 18.dp,
) {
    if (states.isEmpty()) return
    val current = index.coerceIn(states.indices)
    val active = current != offIndex

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> NxTheme.colors.textSecondary.copy(alpha = 0.4f)
            active   -> NxTheme.colors.primary
            hovered  -> NxTheme.colors.textPrimary
            else     -> NxTheme.colors.textSecondary
        },
        animationSpec = Motion.tap.of(),
        label         = "cycle-tint",
    )
    val wash by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            active   -> NxTheme.colors.primary.copy(alpha = if (hovered) 0.24f else 0.16f)
            hovered  -> NxTheme.colors.textPrimary.copy(alpha = 0.08f)
            else     -> Color.Transparent
        },
        animationSpec = Motion.tap.of(),
        label         = "cycle-wash",
    )

    NxTooltip(text = states[current].label, enabled = enabled, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(wash)
                .clickable(
                    interactionSource = interaction,
                    indication        = null,
                    enabled           = enabled,
                    onClick           = { onCycle((current + 1) % states.size) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                icon               = states[current].icon,
                contentDescription = states[current].label,
                tint               = tint,
                // Filled and a touch heavier, so the ring's active states read as
                // lit rather than merely tinted: an outline glyph in the accent is
                // hard to tell from an outline glyph in the text colour at 18dp.
                fill               = if (active) 1f else 0f,
                weight             = if (active) 500 else 400,
                size               = glyphSize,
            )
        }
    }
}
