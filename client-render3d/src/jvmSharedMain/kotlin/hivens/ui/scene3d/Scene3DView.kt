package hivens.ui.scene3d

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import hivens.ui.render3d.TriBatch
import hivens.ui.render3d.downsampleInto
import hivens.ui.render3d.rasterizeInto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

// Compose host for a scene graph. The scene itself stays plain mutable Kotlin.
// Rendering is asynchronous: the composition thread only flattens the scene to
// screen-space triangles (a cheap tree walk) whenever a snapshot input changes,
// while the CPU rasterizer runs OFF the frame thread and publishes a finished
// bitmap that the draw phase just blits. So a slow frame never blocks painting
// -- the view keeps showing the last completed bitmap until the next is ready
// -- and the rasterizer's scratch buffers are reused frame to frame, not
// reallocated (per-frame reallocation of the supersampled buffers was the
// GC-pause source that stalled the whole UI).

/**
 * The most supersampled pixels one frame may fill. A view larger than this renders smaller and is
 * stretched to its size when drawn: the CPU rasterizer's cost follows the pixel count, and a phone's
 * dense screen would otherwise ask it for several times what a desktop window does.
 */
internal expect fun renderPixelBudget(): Long

/**
 * The shortest gap between two rasterized frames, in milliseconds. A phone spends a core on the
 * rasterizer when it is let run every display frame, and an idling character does not need that.
 */
internal expect fun minRenderIntervalMs(): Long

private fun renderSizeFor(canvas: IntSize, supersample: Int): IntSize {
    if (canvas.width <= 0 || canvas.height <= 0) return IntSize.Zero
    val pixels = canvas.width.toLong() * canvas.height * supersample * supersample
    val budget = renderPixelBudget()
    if (pixels <= budget) return canvas
    val k = sqrt(budget.toDouble() / pixels)
    return IntSize((canvas.width * k).toInt().coerceAtLeast(1), (canvas.height * k).toInt().coerceAtLeast(1))
}

private const val TWO_PI = (2.0 * PI).toFloat()
private const val PITCH_LIMIT = 1.2f

@Stable
class Scene3DState(val root: Node = Node()) {
    var cameraYaw: Float by mutableFloatStateOf(0f)

    private val pitchState = mutableFloatStateOf(0f)
    var cameraPitch: Float
        get() = pitchState.value
        set(value) { pitchState.value = value.coerceIn(-PITCH_LIMIT, PITCH_LIMIT) }

    internal var revision: Int by mutableIntStateOf(0)

    /** Mutate the graph (attach/detach, transforms, meshes) through this so
     *  the hosting view redraws. Returns the block's result. */
    fun <T> update(block: Scene3DState.() -> T): T {
        val result = block()
        revision++
        return result
    }
}

@Composable
fun rememberScene3DState(build: Scene3DState.() -> Unit = {}): Scene3DState =
    remember { Scene3DState().apply(build) }

/**
 * Renders [state]'s scene through [cameraFor] (called with the pixel size; read
 * snapshot state inside it to drive the camera). When [interactive], drag orbits
 * the built-in camera angles. [prepareFrame] runs before each flatten -- the
 * hook a rig uses to apply the current pose; snapshot reads inside it (and inside
 * [cameraFor]) drive re-renders like any other. [supersample] is the SSAA factor
 * (1 = raw aliased output).
 *
 * The flatten runs on the composition thread; the rasterize runs on
 * [Dispatchers.Default] and the finished bitmap is published for the draw phase
 * to blit. A render in flight never blocks the frame -- the previous bitmap is
 * shown until the new one lands -- and [conflate] drops intermediate requests so
 * a 60 fps animation never queues renders faster than they complete.
 */
@Composable
fun Scene3DView(
    state: Scene3DState,
    modifier: Modifier = Modifier,
    cameraFor: (w: Int, h: Int) -> OrthoCamera,
    interactive: Boolean = true,
    prepareFrame: (() -> Unit)? = null,
    supersample: Int = 2,
) {
    val gestureModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                state.cameraYaw = (state.cameraYaw + drag.x * 0.01f) % TWO_PI
                state.cameraPitch += -drag.y * 0.01f
            }
        }
    } else {
        Modifier
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val renderer = remember { SceneRenderer() }
    // rememberUpdatedState so the pump always calls the latest hooks without
    // restarting the effect (SkinView3D passes fresh lambdas each recomposition).
    val camera by rememberUpdatedState(cameraFor)
    val prepare by rememberUpdatedState(prepareFrame)

    LaunchedEffect(state, renderer, supersample) {
        snapshotFlow {
            val ss = supersample.coerceAtLeast(1)
            val (w, h) = renderSizeFor(canvasSize, ss)
            if (w <= 0 || h <= 0) {
                null
            } else {
                state.revision                     // observe structural scene changes
                prepare?.invoke()                  // apply the pose (deterministic in the pose clock)
                val cam = camera(w, h)             // observe yaw / pitch / pose clock
                RenderRequest(
                    w, h, ss,
                    collectTriBatches(
                        state.root, cam.yaw, cam.pitch,
                        cam.scale * ss, cam.centerX * ss, cam.centerY * ss,
                    ),
                )
            }
        }
            .conflate()
            .collect { req ->
                val started = System.nanoTime()
                frame = req?.let { withContext(Dispatchers.Default) { renderer.render(it) } }
                val spare = minRenderIntervalMs() - (System.nanoTime() - started) / 1_000_000L
                if (spare > 0L) delay(spare.milliseconds)
            }
    }

    Box(
        modifier
            .then(gestureModifier)
            .onSizeChanged { canvasSize = it }
            .drawBehind { frame?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) } },
    )
}

/** One frame's flattened geometry, detached from the mutable scene graph so the
 *  off-thread rasterize can never race a pose mutation on the composition thread. */
private class RenderRequest(
    val w: Int,
    val h: Int,
    val ss: Int,
    val batches: List<TriBatch>,
)

/**
 * Owns the rasterizer's scratch buffers -- the supersampled colour + depth
 * buffers, the resolve buffer, and the ARGB [ArgbImage] -- and reuses them
 * across frames, reallocating only when the pixel size changes. An animating
 * view therefore churns no per-frame raster garbage. Not thread-safe: driven
 * from the single render coroutine in [Scene3DView].
 */
private class SceneRenderer {
    private var sw = -1
    private var sh = -1
    // The output size and the factor too, not only their product: half the canvas at
    // twice the supersampling keeps the supersampled size and changes the resolve
    // buffer and the image, which a check on the product alone kept stale.
    private var ow = -1
    private var oh = -1
    private var oss = -1
    private var color = IntArray(0)
    private var depth = FloatArray(0)
    private var resolved = IntArray(0)
    private var image: ArgbImage? = null

    fun render(req: RenderRequest): ImageBitmap {
        val rw = req.w * req.ss
        val rh = req.h * req.ss
        if (rw != sw || rh != sh || req.w != ow || req.h != oh || req.ss != oss) {
            sw = rw; sh = rh
            ow = req.w; oh = req.h; oss = req.ss
            color = IntArray(rw * rh)
            depth = FloatArray(rw * rh)
            resolved = if (req.ss == 1) IntArray(0) else IntArray(req.w * req.h)
            image = ArgbImage(req.w, req.h)
        }
        rasterizeInto(req.batches, rw, rh, color, depth)
        val out = if (req.ss == 1) color else { downsampleInto(color, rw, rh, req.ss, resolved); resolved }
        return image!!.toImageBitmap(out)
    }
}
