package hivens.ui.nx

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import hivens.ui.theme.Motion

/**
 * Work in progress drawn inside the control that waits on it, rather than in a bar
 * beside it: a fill from the start edge up to [progress], or, for work whose size is
 * not known, a band a third of the width crossing it on [Motion.sweep].
 *
 * Drawn behind the content and inside whatever clip the caller set, so it takes the
 * control's own shape. [ink] is the caller's, since only the control knows what its
 * fill is drawn over.
 */
@Composable
fun Modifier.workProgress(progress: Float?, ink: Color): Modifier {
    if (progress != null) {
        val shown by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.track, label = "workProgress")
        return drawBehind { drawRect(ink, size = Size(size.width * shown, size.height)) }
    }
    val transition = rememberInfiniteTransition(label = "workSweep")
    val phase by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(Motion.sweep.of(), RepeatMode.Restart),
        label         = "workSweepPhase",
    )
    return drawBehind {
        val w = size.width / 3f
        val x = (size.width + w) * phase - w
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, ink, Color.Transparent), startX = x, endX = x + w),
            topLeft = Offset(x, 0f),
            size    = Size(w, size.height),
        )
    }
}
