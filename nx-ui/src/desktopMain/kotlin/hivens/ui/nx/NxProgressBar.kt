package hivens.ui.nx

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * The measure primitive. A track and the part of it that is done.
 *
 * Deliberately not Material's `LinearProgressIndicator`, for two reasons that
 * are visible rather than architectural. It draws a stop indicator -- a dot
 * parked at the far end of the track -- which on a small panel reads as a speck
 * of dirt rather than as information. And its corner is fixed, so it stays
 * rounded under a style whose whole premise is hard edges.
 *
 * The corner here comes from the active style's badge spec, the same place every
 * other small shell in the app takes it: full round under Celestia, square under
 * Brut. That is why it follows the style axis without inventing a token of its
 * own.
 *
 * [progress] outside 0..1 is clamped; null means the size of the job is not
 * known yet. An unknown job sweeps a segment along the track, except under a
 * style with motion off, where it settles into a dimmed full track instead --
 * a still bar that reads as busy, rather than an animation that ignores the
 * style or a frozen segment that reads as a stalled percentage.
 */
@Composable
fun NxProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = NxTheme.colors.progressAccent,
    trackColor: Color = NxTheme.colors.textSecondary.copy(alpha = 0.22f),
) {
    val style = LocalStyle.current
    val corner = style.badgeStyle.corner
    val still = style.animationMultiplier == 0f

    // NaN passes coerceIn -- both of its comparisons are false against NaN -- and
    // then becomes the animation's current value, so every later valid value
    // interpolates from it and the bar stays empty for good, silently. A job whose
    // size is not known is what null already means, so NaN joins it.
    val target = progress?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)

    // Eased so a coarse feed (a file finishing, a block landing) does not step
    // the bar. Motion-off collapses the tween to 1ms, so the value still lands.
    val fraction by animateFloatAsState(
        targetValue    = target ?: 0f,
        animationSpec  = tween(style.animationDurationMs(220), easing = LinearEasing),
        label          = "nxProgress",
    )

    val sweep = if (target == null && !still) {
        val transition = rememberInfiniteTransition(label = "nxProgressSweep")
        transition.animateFloat(
            initialValue  = -INDETERMINATE_SPAN,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(style.animationDurationMs(1_150), easing = LinearEasing),
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
            // Unknown job, motion off: a dimmed full track. Busy, not a percentage.
            still -> drawRoundRect(color = color.copy(alpha = 0.35f), cornerRadius = radius)
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
