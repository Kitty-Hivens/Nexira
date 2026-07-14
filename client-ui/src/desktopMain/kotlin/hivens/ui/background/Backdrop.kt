package hivens.ui.background

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared description of the wallpaper layer so a surface can redraw a blurred
 * slice of it (real backdrop frost) instead of faking glass with a flat tint.
 *
 * We own the bitmap (decoded in [CustomBackground]), so the frost does NOT need
 * a generic backdrop-capture: a surface redraws THIS image with the same
 * transform, shifted by its own window origin, then blurs and clips. The
 * transform helpers below are the single source of truth shared with
 * [AnimatedParallaxImage] so the slice can never drift from the real draw.
 *
 * [mouse] is read live by the slice so parallax stays in lockstep; everything
 * else changes only when the wallpaper or its settings change.
 */
@Immutable
class BackdropState(
    val bitmap: ImageBitmap? = null,
    val contentScale: ContentScale = ContentScale.Crop,
    val alignment: Alignment = Alignment.Center,
    val opacity: Float = 1f,
    val bgBlurRadiusDp: Float = 0f,
    val darken: Float = 0f,
    val tint: Color? = null,
    val tintOpacity: Float = 0f,
    // -1 = grayscale, 0 = unchanged, +1 = oversaturated (maps to setToSaturation(1 + v)).
    val saturation: Float = 0f,
    val parallaxIntensity: Float = 0f,
    val isAnimated: Boolean = false,
    // Material-You palette seed (ARGB) extracted from the wallpaper -- the dominant
    // colour. Null when none could be extracted; static + video both fill it (video
    // from its first decoded frame). The theme reads it to seed the palette.
    val seedArgb: Int? = null,
    // Average brightness (0..1) of the wallpaper -- distinct from [seedArgb] (the vivid
    // dominant). Null when no image. The theme reads it to match dark/light.
    val avgLuminance: Float? = null,
    val mouse: () -> Offset = { Offset(0.5f, 0.5f) },
) {
    companion object {
        val EMPTY = BackdropState()
    }
}

/**
 * The active wallpaper recipe. compositionLocalOf (not static) so only the
 * surfaces that actually read it recompose when the wallpaper changes -- it
 * changes rarely (image / settings), and the per-frame parallax is read through
 * [BackdropState.mouse] without churning the local.
 */
val LocalBackdrop = compositionLocalOf { BackdropState.EMPTY }

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

/**
 * Draws a blurred slice of the active wallpaper aligned to this surface's
 * position, for use as the bottom layer of a frosted surface. Fills the parent;
 * call inside a Box with `Modifier.matchParentSize()`.
 *
 * Static wallpaper: cheap (Skia caches the blurred layer while inputs are
 * stable). Animated wallpaper: skipped here -- per-frame reblur is too costly,
 * so [FrostSurface] leans on a heavier scrim instead (see [BackdropState.isAnimated]).
 */
@Composable
fun FrostBackdrop(extraBlurDp: Float, modifier: Modifier = Modifier) {
    val st = LocalBackdrop.current
    val bmp = st.bitmap ?: return
    if (st.isAnimated) return
    val win = LocalWindowInfo.current.containerSize
    if (win.width <= 0 || win.height <= 0) return
    val density = LocalDensity.current

    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = parallaxTranslationFor(st.mouse(), st.parallaxIntensity)
    val px by animateFloatAsState(target.x, spring(stiffness = 50f, dampingRatio = 0.8f), label = "frostParX")
    val py by animateFloatAsState(target.y, spring(stiffness = 50f, dampingRatio = 0.8f), label = "frostParY")
    val pScale = parallaxScaleFor(st.parallaxIntensity)

    // Mirror the wallpaper's saturation so the slice matches the real draw.
    val saturationFilter = remember(st.saturation) { bgSaturationFilter(st.saturation) }

    // The painter survives recomposition: a fresh instance per pass busts the
    // layer cache below and every BackdropState tick re-uploads the bitmap.
    val painter = remember(bmp) { BitmapPainter(bmp) }
    Box(modifier.clipToBounds().onGloballyPositioned { origin = it.positionInWindow() }) {
        Image(
            painter            = painter,
            contentDescription = null,
            contentScale       = st.contentScale,
            alignment          = st.alignment,
            colorFilter        = saturationFilter,
            modifier           = Modifier
                .size(with(density) { win.width.toDp() }, with(density) { win.height.toDp() })
                .offset { IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()) }
                .graphicsLayer {
                    scaleX = pScale; scaleY = pScale
                    translationX = px; translationY = py
                }
                // alpha outside the blur: an opacity tick recomposites the
                // cached blurred slice instead of re-blurring it (see the same
                // ordering note in CustomBackground).
                .alpha(st.opacity)
                .blur((st.bgBlurRadiusDp + extraBlurDp).dp, BlurredEdgeTreatment.Unbounded),
        )
        // Mirror CustomBackground's darken + tint overlays so the slice matches
        // the real wallpaper's tone, not just its pixels.
        if (st.darken > 0f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = st.darken)))
        }
        st.tint?.let { tint ->
            if (st.tintOpacity > 0f) Box(Modifier.matchParentSize().background(tint.copy(alpha = st.tintOpacity)))
        }
    }
}
