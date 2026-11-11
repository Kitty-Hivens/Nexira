package hivens.ui.background

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxColor
import hivens.ui.theme.WallpaperTone
import hivens.ui.widgets.toWidgetColorOrNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * What the host makes of the wallpaper file: the picture to draw, or null while it is still
 * being read. It also reports the wallpaper's colours and brightness through [onTone] once the
 * picture says something, and, where the host can play one, a video's own volume through
 * [onAudioVolume].
 *
 * Everything that is the same on every host (the effects, the scale, the parallax) is drawn from
 * this, so a host brings a decoder and nothing else.
 */
@Composable
internal expect fun rememberWallpaperPainter(
    file: File,
    settings: BackgroundSettings,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
): Painter?

/**
 * Renders a custom background wallpaper behind the main app content.
 *
 * Supports: blur, darkening, opacity, parallax, vignette, color tint,
 * multiple scale modes and alignment control. A picture that moves plays
 * through Skinema (see [rememberSkinemaFrame]); a still is decoded once,
 * downscaled and cached, by Skia where Skia reads the format and by the media
 * decoder where it does not (see [backgroundMediaKind]).
 */
@Composable
fun CustomBackground(
    settings: BackgroundSettings,
    modifier: Modifier = Modifier,
    mousePosProvider: () -> Offset = { Offset(0.5f, 0.5f) },
    onTone: (WallpaperTone) -> Unit = {},
    /**
     * Where a level set from a player widget goes, while the wallpaper is what the
     * transport is pointed at.
     *
     * Narrow rather than the whole settings writer: the painter has no business
     * writing an arbitrary setting, and the one field it can honestly own is the
     * loudness of the thing it is playing.
     */
    onAudioVolume: (Float) -> Unit = {},
) {
    if (!settings.hasUsableImage()) {
        LaunchedEffect(Unit) { onTone(WallpaperTone.NONE) }
        return
    }
    val file = File(settings.imagePath!!)
    // The darkening is the theme's page colour, not black: it is the one place the
    // theme reaches a page that has a picture on it, and black left every theme
    // looking the same over the same wallpaper.
    val page = NxColor.page

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedParallaxImage(
            file             = file,
            settings         = settings,
            mousePosProvider = mousePosProvider,
            onTone           = onTone,
            onAudioVolume    = onAudioVolume,
        )

        // Darkening overlay, in the page colour.
        if (settings.darkenAmount > 0f) {
            Box(Modifier.fillMaxSize().background(page.copy(alpha = settings.darkenAmount)))
        }

        // Color tint overlay
        if (settings.tintColor != null && settings.tintOpacity > 0f) {
            // The parser every other colour the launcher reads goes through. Prefixed
            // with FF by hand, a three-digit value parsed as a wrong colour, and an
            // eight-digit one lost its own alpha.
            val tint = settings.tintColor.toWidgetColorOrNull()
            if (tint != null) {
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = settings.tintOpacity)))
            }
        }

        // Vignette overlay
        if (settings.vignetteIntensity > 0f) {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawIntoCanvas {
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        val radius  = maxOf(size.width, size.height) * 0.7f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    page.copy(alpha = settings.vignetteIntensity * 0.6f)
                                ),
                                center = Offset(centerX, centerY),
                                radius = radius
                            ),
                            center = Offset(centerX, centerY),
                            radius = radius * 1.5f
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AnimatedParallaxImage(
    file: File,
    settings: BackgroundSettings,
    mousePosProvider: () -> Offset,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
) {
    // Scale mode and alignment (Backdrop.kt) decide where every pixel of the
    // wallpaper lands.
    val contentScale = bgContentScale(settings.scaleMode)
    val alignment = bgAlignment(settings.alignX, settings.alignY)

    // What the host decodes the file to, and what it reports of its colours. Null until it is
    // known, so nothing is drawn for a frame rather than composition waiting on a decode.
    val painter: Painter? = rememberWallpaperPainter(file, settings, onTone, onAudioVolume)
    if (painter == null) return

    // Saturation applies at the Image, so it covers the static painter and every
    // video frame alike.
    val saturationFilter = remember(settings.saturation) { bgSaturationFilter(settings.saturation) }
    // alpha OUTSIDE the blur (leftmost = outermost): an opacity tick then only
    // recomposites the cached blurred layer. With alpha inside, every tick of
    // the opacity slider invalidated the blur's input and re-blurred the whole
    // wallpaper -- the slider-drag jank. Uniform alpha commutes with a linear
    // blur, so the output is identical.
    val baseModifier = Modifier
        .fillMaxSize()
        .alpha(settings.opacity)
        .let {
            if (settings.blurRadius > 0f)
                it.blur(settings.blurRadius.dp, BlurredEdgeTreatment.Unbounded)
            else it
        }

    val parallax = rememberParallaxOffset(mousePosProvider, settings.parallaxIntensity)
    Image(
        painter            = painter,
        contentDescription = null,
        contentScale       = contentScale,
        alignment          = alignment,
        colorFilter        = saturationFilter,
        modifier           = if (parallax == null) baseModifier else baseModifier.graphicsLayer {
            val extraScale = parallaxScaleFor(settings.parallaxIntensity)
            scaleX       = extraScale
            scaleY       = extraScale
            // Read here and nowhere else: graphicsLayer runs in the draw phase, so
            // the pointer moving invalidates a draw rather than a composition.
            translationX = parallax.x.value
            translationY = parallax.y.value
        },
    )
}

internal class ParallaxOffset(val x: Animatable<Float, AnimationVector1D>, val y: Animatable<Float, AnimationVector1D>)

/**
 * The parallax translation, animated off-composition. Null when parallax is off.
 *
 * Parallax is a view transform over pixels that are already drawn, and
 * [graphicsLayer] applies it at composite time for free. What was not free was
 * asking the pointer for its position in the composable body: that is a snapshot
 * read during composition, so every mouse move recomposed this whole subtree, the
 * video player included. The spring runs in a coroutine now and the value is read
 * only where it is used.
 */
@Composable
internal fun rememberParallaxOffset(mouse: () -> Offset, intensity: Float): ParallaxOffset? {
    if (intensity <= 0f) return null
    val latestMouse by rememberUpdatedState(mouse)
    val offset = remember { ParallaxOffset(Animatable(0f), Animatable(0f)) }
    LaunchedEffect(intensity) {
        snapshotFlow { parallaxTranslationFor(latestMouse(), intensity) }
            // Each target is launched, not awaited. Animatable retargets through its
            // own mutex: the running animation is cancelled by the next animateTo and
            // the next one continues from the current value AND velocity.
            //
            // collectLatest here instead cancelled the whole coroutine on every
            // emission, and a moving pointer emits once a frame -- so the spring was
            // cancelled before it was ever handed a frame and the offset stayed at
            // zero for as long as the pointer kept moving. It only travelled once the
            // pointer stopped, which is the opposite of what parallax is.
            .collect { target ->
                @Suppress("SuspiciousImplicitCoroutineScopeReceiverAccess")
                launch { offset.x.animateTo(target.x, PARALLAX_SPRING) }
                @Suppress("SuspiciousImplicitCoroutineScopeReceiverAccess")
                launch { offset.y.animateTo(target.y, PARALLAX_SPRING) }
            }
    }
    return offset
}

private val PARALLAX_SPRING = spring<Float>(stiffness = 50f, dampingRatio = 0.8f)

