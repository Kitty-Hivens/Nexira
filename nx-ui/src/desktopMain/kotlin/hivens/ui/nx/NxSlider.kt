package hivens.ui.nx

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.customization.sliderKeyboardAdjust
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * A labeled slider whose track is **bounded** so it never runs to the window
 * edge on a wide display (Rule 6/D08). The header row carries [label] and
 * [valueText]; the track sits below, capped at [maxTrackWidth] and left-aligned,
 * with hover + arrow-key fine adjustment.
 *
 * [compact] is the panel form: the label drops to the caption size and the touch
 * band tightens. A popover panel is 268dp wide and its own heading is set in
 * labelLarge, so the default label -- body size, medium weight -- read as the
 * loudest thing in the panel and inverted the hierarchy.
 */
@Composable
fun NxSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    keyStep: Float = (range.endInclusive - range.start) / 100f,
    maxTrackWidth: Dp = 420.dp,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style      = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyLarge,
                color      = if (enabled) NxTheme.colors.textPrimary else NxTheme.colors.textSecondary,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f, fill = false).padding(end = Spacing.s8),
            )
            Text(
                valueText,
                style    = MaterialTheme.typography.bodySmall,
                color    = NxTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // widthIn caps the max constraint, fillMaxWidth fills within it: the track
        // grows to the section width but never past maxTrackWidth, so it stops well
        // short of the monitor edge on a 2560 display.
        NxSliderTrack(
            value         = value,
            range         = range,
            onValueChange = onValueChange,
            enabled       = enabled,
            compact       = compact,
            modifier      = Modifier
                .widthIn(max = maxTrackWidth)
                .fillMaxWidth()
                .sliderKeyboardAdjust(value, range, keyStep, onValueChange),
        )
    }
}

/** The track's own line weight, and the thumb at rest, hovered and under a drag. */
private val TRACK_HEIGHT = 4.dp
private val THUMB_REST = 6.5.dp
private val THUMB_HOVER = 8.dp
private val THUMB_DRAG = 9.5.dp

/**
 * The bare track, drawn rather than borrowed.
 *
 * Material's own slider is a different object than the rest of this library: its
 * thumb is a vertical bar separated from the track by a gap, it prints a stop dot
 * at the far end, and its track is four times the weight of every other line the
 * interface draws. Inside a 268dp panel that reads as a broken control rather than
 * as a loudness. What is here instead is one line and one knob: the fill ends in
 * the thumb, so the played part and the handle are one shape, and the knob grows
 * under the pointer so the affordance arrives before the drag does.
 *
 * The travel is inset by the thumb's largest radius on both ends, so the knob never
 * hangs off the end of its own track, and the value maps to that inset span rather
 * than to the full width -- otherwise the ends are unreachable by exactly a radius.
 */
@Composable
fun NxSliderTrack(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val width = remember { mutableFloatStateOf(0f) }
    val palette = NxTheme.colors
    // The travel is inset in pixels, and the pointer reports pixels: mixing the dp
    // magnitude in here put the zero of the scale a radius away from the track's own
    // start on every display that is not at 1x.
    val insetPx = with(LocalDensity.current) { THUMB_DRAG.toPx() }

    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val report = rememberUpdatedState<(Float) -> Unit> { x ->
        val travel = width.floatValue - 2f * insetPx
        if (travel > 0f) {
            val f = ((x - insetPx) / travel).coerceIn(0f, 1f)
            onValueChange(range.start + f * span)
        }
    }

    val thumb by animateDpAsState(
        targetValue = when {
            !enabled -> THUMB_REST * 0.8f
            dragging -> THUMB_DRAG
            hovered  -> THUMB_HOVER
            else     -> THUMB_REST
        },
        animationSpec = Motion.tap.of(),
        label         = "sliderThumb",
    )

    val active = if (enabled) palette.primary else palette.textSecondary.copy(alpha = 0.35f)
    val rest = palette.outline.copy(alpha = if (hovered && enabled) 0.32f else 0.2f)

    Box(
        modifier
            // A band, not a hairline: a 4dp line is not a pointer target. The band is
            // what the pointer hits, and the line is drawn down its middle.
            .height(if (compact) 20.dp else 24.dp)
            .hoverable(interaction, enabled = enabled)
            .onSizeChanged { width.floatValue = it.width.toFloat() }
            // A tap anywhere on the band jumps there, which is what a volume bar is
            // for: one click to half loud, rather than a drag from wherever it stands.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { report.value(it.x) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart  = { dragging = true; report.value(it.x) },
                    onDragEnd    = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> report.value(change.position.x) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = THUMB_DRAG.toPx()
            val travel = (size.width - 2f * inset).coerceAtLeast(0f)
            val cy = size.height / 2f
            val x = inset + travel * fraction
            val line = TRACK_HEIGHT.toPx()
            drawLine(
                color = rest,
                start = Offset(inset, cy),
                end = Offset(inset + travel, cy),
                strokeWidth = line,
                cap = StrokeCap.Round,
            )
            if (fraction > 0f) {
                drawLine(
                    color = active,
                    start = Offset(inset, cy),
                    end = Offset(x, cy),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(color = active, radius = thumb.toPx(), center = Offset(x, cy))
            // A hairline of the same ink at a quarter strength, so the knob keeps an
            // edge where the fill behind it is the identical colour.
            if (enabled) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f),
                    radius = thumb.toPx(),
                    center = Offset(x, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}
