package hivens.ui.nx

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme

/**
 * The measure primitive. A track and the part of it that is done.
 *
 * Deliberately not Material's `LinearProgressIndicator`. It draws a stop
 * indicator, a dot parked at the far end of the track, which on a small panel
 * reads as a speck of dirt rather than as information. Its corner is also fixed,
 * so it cannot follow the rest of the interface.
 *
 * The corner is fully round, which is what a measure reads as at this height.
 *
 * [progress] outside 0..1 is clamped; null means the size of the job is not
 * known yet, and an unknown job sweeps a segment along the track.
 */
@Composable
fun NxProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = NxTheme.colors.progressAccent,
    trackColor: Color = NxTheme.colors.textSecondary.copy(alpha = 0.22f),
) {
    val corner = CornerSize(50)

    // NaN passes coerceIn -- both of its comparisons are false against NaN -- and
    // then becomes the animation's current value, so every later valid value
    // interpolates from it and the bar stays empty for good, silently. A job whose
    // size is not known is what null already means, so NaN joins it.
    val target = progress?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)

    // Eased so a coarse feed (a file finishing, a block landing) does not step
    // the bar.
    val fraction by animateFloatAsState(
        targetValue    = target ?: 0f,
        animationSpec  = Motion.colorShift,
        label          = "nxProgress",
    )

    val sweep = if (target == null) {
        val transition = rememberInfiniteTransition(label = "nxProgressSweep")
        transition.animateFloat(
            initialValue  = -INDETERMINATE_SPAN,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = Motion.sweep.of(),
                repeatMode = RepeatMode.Restart,
            ),
            label = "nxProgressSweepValue",
        ).value
    } else {
        null
    }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val radiusPx = corner.toPx(Size(size.width, size.height), this)
        val radius = CornerRadius(radiusPx, radiusPx)

        drawRoundRect(color = trackColor, cornerRadius = radius)

        when {
            // Known job: fill from the start edge.
            target != null -> {
                val w = size.width * fraction
                if (w > 0f) {
                    drawRoundRect(color = color, size = Size(w, size.height), cornerRadius = radius)
                }
            }
            // Unknown job: a segment crossing the track, clipped at both ends.
            else -> {
                val start = ((sweep ?: 0f) * size.width).coerceAtLeast(0f)
                val end = (((sweep ?: 0f) + INDETERMINATE_SPAN) * size.width).coerceAtMost(size.width)
                if (end > start) {
                    drawRoundRect(
                        color        = color,
                        topLeft      = Offset(start, 0f),
                        size         = Size(end - start, size.height),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

/** Share of the track the indeterminate segment covers. */
private const val INDETERMINATE_SPAN = 0.28f
