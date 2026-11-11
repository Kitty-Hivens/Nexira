package hivens.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Canvas as SkCanvas

/**
 * The Skia filter that blurs whatever is already on the canvas beneath the surface.
 *
 * `saveLayer` with a backdrop filter seeds a new layer with a filtered copy of the
 * destination, then composites it straight back. Compose has no modifier for this;
 * Skia does, and skiko exposes it, so the whole operation is one call in the draw phase
 * and needs no knowledge of what it is blurring.
 *
 * A backdrop filter reads the CURRENT layer. Any ancestor with alpha below 1 puts the
 * surface in an offscreen layer of its own, and the filter then finds it empty and
 * draws nothing. Alpha exactly 1 creates no layer, and scale, rotation and clipping do
 * not isolate; only alpha does.
 *
 * The result is not cacheable, because the destination it filters can change every
 * frame, which is why [hivens.ui.customization.CustomizationSettings.surfaceBlur]
 * switches the whole thing off in one place instead of per surface.
 */
@Composable
internal actual fun rememberBackdropBlur(radiusDp: Float): BackdropBlur? {
    if (radiusDp <= 0f || !LocalCustomization.current.surfaceBlur) return null
    val density = LocalDensity.current
    // One native filter per radius per surface, not one per frame.
    val filter = remember(radiusDp, density) {
        val sigma = with(density) { radiusDp.dp.toPx() }
        ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
    }
    DisposableEffect(filter) { onDispose { filter.close() } }
    return remember(filter) { BackdropBlur { scope -> scope.drawBackdrop(filter) } }
}

private fun DrawScope.drawBackdrop(filter: ImageFilter) {
    drawIntoCanvas { canvas ->
        val native = canvas.skiaCanvas
        native.saveLayer(SkCanvas.SaveLayerRec(bounds = Rect.makeWH(size.width, size.height), backdrop = filter))
        native.restore()
    }
}
