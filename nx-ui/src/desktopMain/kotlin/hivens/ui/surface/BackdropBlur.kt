package hivens.ui.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Canvas as SkCanvas

/**
 * Blurs whatever is already on the canvas beneath this element.
 *
 * `saveLayer` with a backdrop filter seeds a new layer with a filtered copy of the
 * destination, then composites it straight back. Compose has no modifier for this;
 * Skia does, and skiko exposes it, so the whole operation is one call in the draw
 * phase and needs no knowledge of what it is blurring.
 *
 * That last part is the point. The layer this backs asks for a radius and nothing
 * else, and it used to be answered by redrawing the wallpaper at this element's
 * offset -- an answer narrower than the question, which is why a plane over another
 * plane showed the wallpaper it could not see rather than the plane it covered.
 *
 * Two things are worth knowing before relying on it.
 *
 * A backdrop filter reads the CURRENT layer. Any ancestor with alpha below 1 puts
 * this element in an offscreen layer of its own, and the filter then finds it empty
 * and draws nothing. Alpha exactly 1 creates no layer, and scale, rotation and
 * clipping do not isolate; only alpha does. In this shell that means chrome always
 * blurs, and content inside a screen being swapped loses its blur for the length of
 * the fade it is already disappearing into.
 *
 * The result is not cacheable, because the destination it filters can change every
 * frame and Skia has no way to know that it did not. That is inherent to the
 * operation rather than a property of this implementation.
 *
 * Bleed past the element is left to the caller's clip: [FrostSurface] draws this
 * inside a box clipped to the surface's shape, so the blur ends where the shape
 * does rather than at its bounding box.
 */
@Composable
internal fun BackdropBlur(radiusDp: Float, modifier: Modifier) {
    if (radiusDp <= 0f) return
    val density = LocalDensity.current
    // One native filter per radius per surface, not one per frame: building it
    // inside the draw lambda allocates a Skia object on every pass and charges the
    // technique for the caller's mistake.
    val filter = remember(radiusDp, density) {
        val sigma = with(density) { radiusDp.dp.toPx() }
        ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
    }
    DisposableEffect(filter) { onDispose { filter.close() } }
    Box(
        modifier.drawBehind {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.saveLayer(SkCanvas.SaveLayerRec(bounds = Rect.makeWH(size.width, size.height), backdrop = filter))
                native.restore()
            }
        },
    )
}
