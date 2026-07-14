package hivens.ui.fx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Feel knobs for [DisintegrateBox] -- the "juice" the designer dials in. The physics
 * (debris arc: per-strip v0 + gravity + rotation + fade; spring-overshoot on return)
 * is fixed and objective; only these numbers change the feel.
 *
 * Extracted, not invented: the debris-arc pattern (each fragment an independent body
 * with its own velocity + gravity + rotation) is the standard "juice" scatter -- see
 * "Juice it or lose it" (Jonasson/Purho) + physics-motion references.
 */
data class DisintegrateSpec(
    val strips: Int = 22,
    val gravity: Float = 2600f,       // px/s^2 -- pulls strips down
    val vxSpread: Float = 520f,       // px/s -- horizontal launch, +/-
    val upwardPop: Float = 320f,      // px/s -- initial upward kick (gives the arc)
    val rotationSpread: Float = 130f, // deg/s -- tumble, +/-
    val durationMs: Int = 900,
    val reassembleStiffness: Float = 240f,
    val reassembleDamping: Float = 0.6f, // < 1 = overshoot ("jelly")
)

/**
 * Slices [content] into horizontal strips that fly off under gravity when [scattered]
 * flips true, and springs back with an overshoot ("jelly") when it flips false.
 *
 * Strips are drawn from a one-shot bitmap snapshot of the content (captured via the
 * standard rememberGraphicsLayer + toImageBitmap path), so the live content is frozen
 * the instant it scatters. Nothing clips the strip canvas, so fragments fly past the
 * box bounds across the surface.
 */
@Composable
fun DisintegrateBox(
    scattered: Boolean,
    modifier: Modifier = Modifier,
    spec: DisintegrateSpec = DisintegrateSpec(),
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val progress = remember { Animatable(0f) } // 0 = intact, 1 = fully scattered

    LaunchedEffect(scattered) {
        if (scattered) {
            snapshot = layer.toImageBitmap()
            progress.snapTo(0f)
            progress.animateTo(1f, tween(spec.durationMs, easing = LinearEasing))
        } else if (snapshot != null) {
            progress.animateTo(
                0f,
                spring(dampingRatio = spec.reassembleDamping, stiffness = spec.reassembleStiffness),
            )
        }
    }

    Box(modifier) {
        // Live content, recorded into the layer for capture; hidden the instant it
        // scatters so only the strips show.
        Box(
            Modifier
                .graphicsLayer { alpha = if (progress.value == 0f) 1f else 0f }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) { content() }

        val bmp = snapshot
        if (bmp != null && progress.value > 0f) {
            Canvas(Modifier.matchParentSize()) {
                val p = progress.value
                val t = p * (spec.durationMs / 1000f) // real seconds into the throw
                val n = spec.strips.coerceAtLeast(1)
                val dstStripH = size.height / n
                val srcStripH = bmp.height.toFloat() / n
                for (i in 0 until n) {
                    // Deterministic per-strip launch (golden-ratio-ish seed) so a strip
                    // keeps the same trajectory across frames without Math.random churn.
                    val rnd = Random(i * -0x61c88647)
                    val vx = (rnd.nextFloat() * 2f - 1f) * spec.vxSpread
                    val vy = -spec.upwardPop * rnd.nextFloat()
                    val omega = (rnd.nextFloat() * 2f - 1f) * spec.rotationSpread
                    val dx = vx * t
                    val dy = vy * t + 0.5f * spec.gravity * t * t
                    val alpha = ((1f - p) * (1f - p)).coerceIn(0f, 1f) // fade late
                    val topDst = i * dstStripH + dy
                    rotate(omega * t, pivot = Offset(size.width / 2f + dx, topDst + dstStripH / 2f)) {
                        drawImage(
                            image = bmp,
                            srcOffset = IntOffset(0, (i * srcStripH).roundToInt()),
                            srcSize = IntSize(bmp.width, srcStripH.roundToInt().coerceAtLeast(1)),
                            dstOffset = IntOffset(dx.roundToInt(), topDst.roundToInt()),
                            dstSize = IntSize(size.width.roundToInt(), dstStripH.roundToInt().coerceAtLeast(1)),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
    }
}
