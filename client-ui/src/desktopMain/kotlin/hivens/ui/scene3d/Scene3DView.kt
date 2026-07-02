package hivens.ui.scene3d

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.image.BufferedImage
import kotlin.math.PI

// Compose host for a scene graph. The scene itself stays plain mutable
// Kotlin; the draw subscribes to exactly three snapshot inputs -- the two
// camera angles and one revision counter bumped by update {} -- so scene
// mutations invalidate the draw and nothing ever recomposes.

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
 * Renders [state]'s scene through [cameraFor] (called with the pixel size on
 * every draw; read snapshot state inside it to drive the camera). When
 * [interactive], drag orbits the built-in camera angles. [prepareFrame] runs
 * before each flatten -- the hook a rig uses to apply the current pose;
 * snapshot reads inside it invalidate the draw like any other.
 */
@Composable
fun Scene3DView(
    state: Scene3DState,
    modifier: Modifier = Modifier,
    cameraFor: (w: Int, h: Int) -> OrthoCamera,
    interactive: Boolean = true,
    prepareFrame: (() -> Unit)? = null,
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

    Box(
        modifier
            .then(gestureModifier)
            .drawWithCache {
                val w = size.width.toInt()
                val h = size.height.toInt()
                if (w <= 0 || h <= 0) {
                    onDrawBehind { }
                } else {
                    state.revision   // subscribe to structural scene changes
                    prepareFrame?.invoke()
                    val bmp = renderScene(state.root, cameraFor(w, h), w, h).toImageBitmap(w, h)
                    onDrawBehind { drawImage(bmp) }
                }
            },
    )
}

internal fun IntArray.toImageBitmap(w: Int, h: Int): ImageBitmap {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, this, 0, w)
    return img.toComposeImageBitmap()
}
