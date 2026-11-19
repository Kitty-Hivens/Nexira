package hivens.ui.nx

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.Motion
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

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

@Preview
@Composable
private fun WorkProgressDarkPreview() = WorkProgressFills(dark = true)

@Preview
@Composable
private fun WorkProgressLightPreview() = WorkProgressFills(dark = false)

@Composable
private fun WorkProgressFills(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(320.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (progress in listOf(0.4f, null)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(NxColor.wash(NxInk.quiet, 0.12f))
                        .workProgress(progress, NxColor.lead().copy(alpha = 0.35f)),
                )
            }
        }
    }
}
