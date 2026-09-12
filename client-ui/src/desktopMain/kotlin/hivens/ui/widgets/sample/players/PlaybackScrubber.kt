package hivens.ui.widgets.sample.players

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme

/**
 * The seek control the new players share.
 *
 * At rest it is a measure and nothing else: a thin filled track with no handle,
 * so a card that is only being looked at is not covered in controls. Under the
 * pointer the track thickens and a handle appears, which is the whole
 * affordance -- the feedback is size and presence rather than colour, so it
 * reads the same on either palette and over arbitrary wallpaper.
 *
 * Reports a fraction rather than a position. The caller owns the duration and
 * therefore owns the arithmetic; a scrubber that took milliseconds would have to
 * be told the length twice, once to draw and once to report.
 *
 * A drag that is cancelled still releases the pressed state, or the handle
 * sticks enlarged after the pointer leaves the window mid-gesture.
 */
@Composable
internal fun PlaybackScrubber(
    fraction: Float,
    enabled: Boolean,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var pressing by remember { mutableStateOf(false) }
    val active = enabled && (hovered || pressing)

    val trackHeight by animateDpAsState(
        targetValue   = if (active) 5.dp else 3.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "scrub-track",
    )
    val handleSize by animateDpAsState(
        targetValue   = when {
            !active  -> 0.dp
            pressing -> 13.dp
            else     -> 11.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "scrub-handle",
    )

    var widthPx by remember { mutableStateOf(0) }
    val shown = fraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(18.dp)
            .hoverable(interaction, enabled = enabled)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressing = true
                    try {
                        val w = size.width.coerceAtLeast(1).toFloat()
                        onSeekFraction((down.position.x / w).coerceIn(0f, 1f))
                        drag(down.id) { change ->
                            onSeekFraction((change.position.x / w).coerceIn(0f, 1f))
                            change.consume()
                        }
                    } finally {
                        pressing = false
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(NxTheme.colors.textSecondary.copy(alpha = 0.22f)),
        )
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) NxTheme.colors.primary
                    else NxTheme.colors.textSecondary.copy(alpha = 0.35f),
                ),
        )
        // Drawn in the on-accent colour, not the accent: a dot of the fill's own
        // hue sitting on the fill cannot read as a handle, and at the end of the
        // track half of it hangs onto the card, so one circle would be
        // compositing over two different grounds.
        if (widthPx > 0 && handleSize > 0.dp) {
            val halfPx = with(LocalDensity.current) { handleSize.toPx() / 2f }
            Box(
                Modifier
                    .offset { IntOffset((shown * widthPx - halfPx).toInt(), 0) }
                    .size(handleSize)
                    .clip(CircleShape)
                    .background(NxTheme.colors.onPrimary),
            )
        }
    }
}
