package hivens.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * GNOME-style circular theme reveal. On toggle: snapshot the current (old-theme) frame,
 * flip the theme so the live content underneath renders the new one, then sweep a
 * growing circular hole in the old-frame overlay out from the toggle's position --
 * the new theme spreads from where you clicked. Pure visual; no theme logic here.
 */
class ThemeRevealController internal constructor(
    internal val layer: GraphicsLayer,
    private val scope: CoroutineScope,
) {
    internal var oldFrame by mutableStateOf<ImageBitmap?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
    internal val progress = Animatable(0f)
    private var running = false

    /**
     * Capture the frame, run [apply] (the actual theme flip), then animate the reveal
     * out from [origin] over [durationMs]. A non-positive duration (motion off) or a
     * re-entrant call just applies the change with no animation.
     */
    fun reveal(origin: Offset, durationMs: Int, apply: () -> Unit) {
        if (durationMs <= 1 || running) {
            apply()
            return
        }
        running = true
        scope.launch {
            try {
                val snapshot = layer.toImageBitmap()
                this@ThemeRevealController.origin = origin
                oldFrame = snapshot
                progress.snapTo(0f)
                apply()
                progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
            } finally {
                oldFrame = null
                running = false
            }
        }
    }
}

@Composable
fun rememberThemeReveal(): ThemeRevealController {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    return remember(layer) { ThemeRevealController(layer, scope) }
}

/** A toggle reads this to request a reveal from its own position; null = no host. */
val LocalThemeReveal = staticCompositionLocalOf<ThemeRevealController?> { null }

/**
 * Records [content] into the controller's layer (so it can be snapshotted) and paints
 * the reveal overlay on top while a transition is running. Wrap the themed app content
 * in this; provides [LocalThemeReveal] to everything inside.
 */
@Composable
fun ThemeRevealHost(controller: ThemeRevealController, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalThemeReveal provides controller) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        controller.layer.record { this@drawWithContent.drawContent() }
                        drawLayer(controller.layer)
                    },
            ) { content() }

            val frame = controller.oldFrame
            if (frame != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = cornerDistance(controller.origin, size) * controller.progress.value
                    // Old frame everywhere EXCEPT the growing circle -> the new theme,
                    // already live underneath, shows through the expanding hole.
                    clipPath(Path().apply { addOval(Rect(controller.origin, radius)) }, clipOp = ClipOp.Difference) {
                        drawImage(frame)
                    }
                }
            }
        }
    }
}

/** Distance from [o] to the farthest corner of a [size] box -- the radius that fully covers. */
private fun cornerDistance(o: Offset, size: Size): Float {
    val dx = maxOf(o.x, size.width - o.x)
    val dy = maxOf(o.y, size.height - o.y)
    return hypot(dx, dy)
}
