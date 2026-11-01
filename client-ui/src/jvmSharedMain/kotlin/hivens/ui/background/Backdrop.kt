package hivens.ui.background

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset

// --- Shared transform helpers (single source of truth with CustomBackground) ---

/** Extra scale [AnimatedParallaxImage] applies for parallax headroom. */
fun parallaxScaleFor(intensity: Float): Float = 1f + intensity * 0.15f

/** Parallax translation in px from a normalized (0..1) mouse position. */
fun parallaxTranslationFor(mouse: Offset, intensity: Float): Offset =
    Offset((0.5f - mouse.x) * intensity * 80f, (0.5f - mouse.y) * intensity * 80f)

/** The saturation slider's ColorFilter: -1 -> grayscale, 0 -> none (null), +1 ->
 *  oversaturated. Shared by the wallpaper draw and the frost slice. */
fun bgSaturationFilter(saturation: Float): ColorFilter? =
    saturation.takeIf { it != 0f }?.let {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f + it) })
    }

/** ScaleMode -> ContentScale, shared so the slice scales like the real image. */
fun bgContentScale(mode: ScaleMode): ContentScale = when (mode) {
    ScaleMode.COVER    -> ContentScale.Crop
    ScaleMode.CONTAIN  -> ContentScale.Fit
    ScaleMode.STRETCH  -> ContentScale.FillBounds
    ScaleMode.ORIGINAL -> ContentScale.None
    ScaleMode.TILE     -> ContentScale.None
}

/** Image alignment within its space, shared so the slice aligns like the real image. */
fun bgAlignment(alignX: Float, alignY: Float): Alignment =
    Alignment { size, space, _ ->
        IntOffset(
            ((space.width - size.width) * alignX).toInt(),
            ((space.height - size.height) * alignY).toInt(),
        )
    }
