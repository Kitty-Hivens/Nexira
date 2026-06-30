package hivens.ui.background

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import hivens.ui.theme.seedFromImage
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.makeFromFileName
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("CustomBackground")

/**
 * Renders a custom background wallpaper behind the main app content.
 *
 * Supports: blur, darkening, opacity, parallax, vignette, color tint,
 * multiple scale modes and alignment control. Still images decode through
 * Skia; video and animated images (GIF, APNG, animated WebP) play through
 * Skinema (see [rememberSkinemaFrame]).
 */
@Composable
fun CustomBackground(
    settings: BackgroundSettings,
    modifier: Modifier = Modifier,
    mousePosProvider: () -> Offset = { Offset(0.5f, 0.5f) },
    onBackdrop: (BackdropState) -> Unit = {},
) {
    if (!settings.hasUsableImage()) {
        LaunchedEffect(Unit) { onBackdrop(BackdropState.EMPTY) }
        return
    }
    val file = File(settings.imagePath!!)

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedParallaxImage(
            file             = file,
            settings         = settings,
            mousePosProvider = mousePosProvider,
            onBackdrop       = onBackdrop,
        )

        // Darkening overlay
        if (settings.darkenAmount > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.darkenAmount)))
        }

        // Color tint overlay
        if (settings.tintColor != null && settings.tintOpacity > 0f) {
            val tint = try {
                Color(("FF" + settings.tintColor.removePrefix("#")).toLong(16))
            } catch (_: Exception) { null }
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
                                    Color.Black.copy(alpha = settings.vignetteIntensity * 0.6f)
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
    onBackdrop: (BackdropState) -> Unit,
) {
    // Shared helpers (Backdrop.kt) so a frosted surface reproduces this exact
    // transform when it redraws a blurred slice -- no drift between the two.
    val contentScale = bgContentScale(settings.scaleMode)
    val alignment = bgAlignment(settings.alignX, settings.alignY)

    // Classify off the UI thread (png/webp need a frame-count probe); null
    // until known, so the background draws nothing for a frame rather than
    // blocking composition on file I/O.
    val mediaKind by produceState<BackgroundMediaKind?>(null, file) {
        value = withContext(Dispatchers.IO) { backgroundMediaKind(file) }
    }

    // Still image decodes through Skia; video + animated images play through
    // Skinema. Only the active branch composes, so switching media kind tears
    // down the other's decode/player state.
    val staticBitmap = if (mediaKind == BackgroundMediaKind.Static) rememberStaticImage(file) else null
    // Material-You seed: static from the decoded bitmap (off-thread); video from its
    // first decoded frame (via the player's onSeed). Either feeds BackdropState.seedArgb.
    var videoSeed by remember(file) { mutableStateOf<Int?>(null) }
    val videoPainter = if (mediaKind == BackgroundMediaKind.TimeBased)
        rememberSkinemaFrame(file, settings.animationSpeedMultiplier, settings.loopMode, settings.hardwareDecode, onSeed = { videoSeed = it }) else null
    val staticSeed by produceState<Int?>(null, staticBitmap) {
        value = staticBitmap?.let { bmp -> withContext(Dispatchers.Default) { seedFromImage(bmp) } }
    }
    val seedArgb = staticSeed ?: videoSeed

    val painter: Painter? = when {
        staticBitmap != null -> remember(staticBitmap) { BitmapPainter(staticBitmap) }
        else                 -> videoPainter
    }
    if (painter == null) return

    val isAnimated = mediaKind == BackgroundMediaKind.TimeBased
    val tint = remember(settings.tintColor) {
        settings.tintColor?.let {
            try { Color(("FF" + it.removePrefix("#")).toLong(16)) } catch (_: Exception) { null }
        }
    }
    // Publish the wallpaper recipe for frosted surfaces. A still carries its
    // bitmap so the frost redraws a real blurred slice; time-based publishes a
    // null bitmap + isAnimated so the frost falls back to a scrim (per-frame
    // reblur of video is too costly). Live parallax is read through
    // mousePosProvider so mouse movement does not churn this.
    LaunchedEffect(staticBitmap, settings, isAnimated, seedArgb) {
        onBackdrop(
            BackdropState(
                bitmap            = staticBitmap,
                contentScale      = contentScale,
                alignment         = alignment,
                opacity           = settings.opacity,
                bgBlurRadiusDp    = settings.blurRadius,
                darken            = settings.darkenAmount,
                tint              = tint,
                tintOpacity       = settings.tintOpacity,
                parallaxIntensity = settings.parallaxIntensity,
                isAnimated        = isAnimated,
                seedArgb          = seedArgb,
                mouse             = mousePosProvider,
            ),
        )
    }

    val useParallax = settings.parallaxIntensity > 0f
    val baseModifier = Modifier
        .fillMaxSize()
        .let {
            if (settings.blurRadius > 0f)
                it.blur(settings.blurRadius.dp, BlurredEdgeTreatment.Unbounded)
            else it
        }
        .alpha(settings.opacity)

    if (useParallax) {
        val target = parallaxTranslationFor(mousePosProvider(), settings.parallaxIntensity)
        val parallaxX by animateFloatAsState(target.x, spring(stiffness = 50f, dampingRatio = 0.8f))
        val parallaxY by animateFloatAsState(target.y, spring(stiffness = 50f, dampingRatio = 0.8f))
        Image(
            painter            = painter,
            contentDescription = null,
            contentScale       = contentScale,
            alignment          = alignment,
            modifier           = baseModifier.graphicsLayer {
                val extraScale = parallaxScaleFor(settings.parallaxIntensity)
                scaleX       = extraScale
                scaleY       = extraScale
                translationX = parallaxX
                translationY = parallaxY
            }
        )
    } else {
        Image(
            painter            = painter,
            contentDescription = null,
            contentScale       = contentScale,
            alignment          = alignment,
            modifier           = baseModifier
        )
    }
}

@Composable
private fun rememberStaticImage(file: File): ImageBitmap? {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        if (!file.exists()) return@LaunchedEffect
        withContext(Dispatchers.IO) { bitmap = decodeStaticBackground(file) }
    }
    return bitmap
}

private fun decodeStaticBackground(file: File): ImageBitmap? {
    var data:  Data?  = null
    var codec: Codec? = null
    return try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        decodeFrame(codec, codec.imageInfo, frame = 0)
    } catch (e: Exception) {
        log.error("Failed to decode static background at {}", file.absolutePath, e)
        null
    } finally {
        codec?.close()
        data?.close()
    }
}

private fun decodeFrame(codec: Codec, info: ImageInfo, frame: Int): ImageBitmap {
    val bmp = org.jetbrains.skia.Bitmap().apply { allocPixels(info) }
    return bmp.use { bmp ->
        codec.readPixels(bmp, frame)
        val img = org.jetbrains.skia.Image.makeFromBitmap(bmp)
        img.use { it.toComposeImageBitmap() }
    }
}
