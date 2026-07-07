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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import hivens.ui.render3d.TriBatch
import hivens.ui.render3d.downsampleInto
import hivens.ui.render3d.rasterizeInto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import kotlin.math.PI

// Compose host for a scene graph. The scene itself stays plain mutable Kotlin.
// Rendering is asynchronous: the composition thread only flattens the scene to
// screen-space triangles (a cheap tree walk) whenever a snapshot input changes,
// while the CPU rasterizer runs OFF the frame thread and publishes a finished
// bitmap that the draw phase just blits. So a slow frame never blocks painting
// -- the view keeps showing the last completed bitmap until the next is ready
// -- and the rasterizer's scratch buffers are reused frame to frame, not
// reallocated (per-frame reallocation of the supersampled buffers was the
// GC-pause source that stalled the whole UI).

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
            val w = canvasSize.width
            val h = canvasSize.height
            if (w <= 0 || h <= 0) {
                null
            } else {
                state.revision                     // observe structural scene changes
                prepare?.invoke()                  // apply the pose (deterministic in the pose clock)
                val cam = camera(w, h)             // observe yaw / pitch / pose clock
                val ss = supersample.coerceAtLeast(1)
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
                frame = req?.let { withContext(Dispatchers.Default) { renderer.render(it) } }
            }
    }

    Box(
        modifier
            .then(gestureModifier)
            .onSizeChanged { canvasSize = it }
            .drawBehind { frame?.let { drawImage(it) } },
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
 * buffers, the resolve buffer, and the ARGB [BufferedImage] -- and reuses them
 * across frames, reallocating only when the pixel size changes. An animating
 * view therefore churns no per-frame raster garbage. Not thread-safe: driven
 * from the single render coroutine in [Scene3DView].
 */
private class SceneRenderer {
    private var sw = -1
    private var sh = -1
    private var color = IntArray(0)
    private var depth = FloatArray(0)
    private var resolved = IntArray(0)
    private var image: BufferedImage? = null

    fun render(req: RenderRequest): ImageBitmap {
        val rw = req.w * req.ss
        val rh = req.h * req.ss
        if (rw != sw || rh != sh) {
            sw = rw; sh = rh
            color = IntArray(rw * rh)
            depth = FloatArray(rw * rh)
            resolved = if (req.ss == 1) IntArray(0) else IntArray(req.w * req.h)
            image = BufferedImage(req.w, req.h, BufferedImage.TYPE_INT_ARGB)
        }
        rasterizeInto(req.batches, rw, rh, color, depth)
        val out = if (req.ss == 1) color else { downsampleInto(color, rw, rh, req.ss, resolved); resolved }
        val img = image!!
        img.setRGB(0, 0, req.w, req.h, out, 0, req.w)
        return img.toComposeImageBitmap()
    }
}
