package hivens.ui.skin3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import hivens.ui.theme.LocalStyle
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import kotlin.math.PI

// Live 3D Minecraft-skin view. Renders the raw skin texture on the box model
// from [buildFigure] using orthographic projection: each visible face is a
// parallelogram, so a single Skia drawImageRect under the face's affine
// (Matrix33) maps the texture exactly, with NEAREST sampling for crisp texels.
// Drag rotates; when idle it auto-spins. No baked bitmap, no extra dependency.

private const val TWO_PI = (2.0 * PI).toFloat()

// Radians of yaw per millisecond when auto-spinning (~one turn per 11s).
private const val SPIN_RATE = 0.00055f

// Pitch clamp so the model never flips fully upside down on a vertical drag.
private const val PITCH_LIMIT = 1.2f

/**
 * @param skin        the raw skin texture (64x64 / 64x32 legacy / HD multiples).
 * @param interactive when true, drag rotates the model and pauses the auto-spin.
 * @param autoSpin    when true, the model slowly turns while not being dragged.
 *
 * Slim/Classic and legacy 64x32 are detected from the texture itself.
 */
@Composable
fun SkinView3D(
    skin: ImageBitmap,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    autoSpin: Boolean = true,
) {
    val legacy = remember(skin) { skin.height <= skin.width / 2 }
    val model = remember(skin) {
        val pixels = skin.toPixelMap()
        guessModel(skin.width, skin.height) { x, y -> (pixels[x, y].alpha * 255f).toInt() }
    }
    val figure = remember(model, legacy) { buildFigure(model, legacy) }
    val image = remember(skin) { Image.makeFromBitmap(skin.asSkiaBitmap()) }
    // The Skia Image owns native memory; free it when the skin changes or the
    // view leaves composition instead of waiting for the finalizer.
    DisposableEffect(image) {
        onDispose { image.close() }
    }
    val sampling = remember { FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE) }
    val paint = remember { Paint().apply { isAntiAlias = false } }
    // Paint owns native memory; free it on leave instead of riding the Skia
    // cleaner, matching the deterministic Image disposal above.
    DisposableEffect(Unit) {
        onDispose { paint.close() }
    }

    // Start with a slight three-quarter turn so the face reads as 3D at rest.
    var yaw by remember { mutableFloatStateOf(0.5f) }
    var pitch by remember { mutableFloatStateOf(0.08f) }
    var dragging by remember { mutableStateOf(false) }

    // Honour the style engine's motion token: Brut sets animationMultiplier = 0
    // ("motion off"), so the idle spin stops there and its speed scales with the
    // multiplier under any custom style. Dragging always works regardless.
    val motion = LocalStyle.current.animationMultiplier
    if (autoSpin && motion > 0f) {
        LaunchedEffect(skin, motion) {
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    if (last != 0L && !dragging) {
                        yaw = (yaw + (now - last) * SPIN_RATE * motion) % TWO_PI
                    }
                    last = now
                }
            }
        }
    }

    val gestureModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, drag ->
                change.consume()
                yaw = (yaw + drag.x * 0.01f) % TWO_PI
                pitch = (pitch - drag.y * 0.01f).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier.then(gestureModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        // Figure spans ~33 model units tall and ~18 wide once limbs rotate in;
        // fit to the smaller axis with margin so it never clips.
        val scale = minOf(h / 42f, w / 22f)
        val centerX = w / 2f
        val centerY = h / 2f
        val faces = projectFaces(figure, yaw, pitch, scale, centerX, centerY)
        val k = image.width / 64f

        drawIntoCanvas { canvas ->
            val nc = canvas.skiaCanvas
            faces.forEach { f ->
                val a = f.affine()
                nc.save()
                nc.concat(Matrix33(a.scaleX, a.skewX, a.transX, a.skewY, a.scaleY, a.transY, 0f, 0f, 1f))
                nc.drawImageRect(
                    image,
                    Rect.makeXYWH(f.uv.u * k, f.uv.v * k, f.uv.w * k, f.uv.h * k),
                    Rect.makeXYWH(f.uv.u, f.uv.v, f.uv.w, f.uv.h),
                    sampling,
                    paint,
                    true,
                )
                nc.restore()
            }
        }
    }
}
