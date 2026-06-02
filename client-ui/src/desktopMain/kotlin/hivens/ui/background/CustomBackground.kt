package hivens.ui.background

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.debug.SkiaTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.makeFromFileName
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("CustomBackground")

private const val MAX_ANIMATED_FRAMES        = 240
private const val MAX_ANIMATED_PIXELS_TOTAL  = 1920L * 1080L * 60L

private data class DecodedBg(
    val frames: List<ImageBitmap>,
    val durationsMs: List<Int>,
    val repetitionCount: Int,
)

/**
 * Renders a custom background wallpaper behind the main app content.
 *
 * Supports: blur, darkening, opacity, parallax, vignette, color tint,
 * multiple scale modes, alignment control, and multi-frame animated
 * formats (GIF, APNG, animated WebP) decoded via Skiko Codec.
 */
@Composable
fun CustomBackground(
    settings: BackgroundSettings,
    modifier: Modifier = Modifier,
    mousePosProvider: () -> Offset = { Offset(0.5f, 0.5f) }
) {
    if (!settings.enabled || settings.imagePath.isNullOrBlank()) return
    val file = File(settings.imagePath)
    if (!file.exists()) return

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedParallaxImage(
            file             = file,
            settings         = settings,
            mousePosProvider = mousePosProvider
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
    mousePosProvider: () -> Offset
) {
    val contentScale = when (settings.scaleMode) {
        ScaleMode.COVER    -> ContentScale.Crop
        ScaleMode.CONTAIN  -> ContentScale.Fit
        ScaleMode.STRETCH  -> ContentScale.FillBounds
        ScaleMode.ORIGINAL -> ContentScale.None
        ScaleMode.TILE     -> ContentScale.None
    }

    val alignment = Alignment { size, space, _ ->
        val x = ((space.width - size.width) * settings.alignX).toInt()
        val y = ((space.height - size.height) * settings.alignY).toInt()
        IntOffset(x, y)
    }

    val imageBitmap = rememberSkiaImage(file, settings.animationSpeedMultiplier, settings.loopMode)

    if (imageBitmap != null) {
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
            val mousePos = mousePosProvider()
            val targetX  = (0.5f - mousePos.x) * settings.parallaxIntensity * 80f
            val targetY  = (0.5f - mousePos.y) * settings.parallaxIntensity * 80f
            val parallaxX by animateFloatAsState(targetX, spring(stiffness = 50f, dampingRatio = 0.8f))
            val parallaxY by animateFloatAsState(targetY, spring(stiffness = 50f, dampingRatio = 0.8f))
            Image(
                painter            = BitmapPainter(imageBitmap),
                contentDescription = null,
                contentScale       = contentScale,
                alignment          = alignment,
                modifier           = baseModifier.graphicsLayer {
                    val extraScale = 1f + settings.parallaxIntensity * 0.15f
                    scaleX       = extraScale
                    scaleY       = extraScale
                    translationX = parallaxX
                    translationY = parallaxY
                }
            )
        } else {
            Image(
                painter            = BitmapPainter(imageBitmap),
                contentDescription = null,
                contentScale       = contentScale,
                alignment          = alignment,
                modifier           = baseModifier
            )
        }
    }
}

@Composable
private fun rememberSkiaImage(
    file: File,
    speedMultiplier: Float,
    loopMode: BackgroundLoopMode,
): ImageBitmap? {
    var decoded  by remember(file) { mutableStateOf<DecodedBg?>(null) }
    var frameIdx by remember(file) { mutableStateOf(0) }
    val speedRef = rememberUpdatedState(speedMultiplier)

    LaunchedEffect(file) {
        if (!file.exists()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            decoded = decodeBackground(file) { preview -> decoded = preview }
        }
    }

    val d = decoded ?: return null
    if (d.frames.size <= 1) {
        return d.frames.firstOrNull()
    }

    // Skia spec: repetitionCount = N means N additional plays after the
    // first. -1 = loop forever, 0 = play once. loopMode lets the user
    // override the codec's stored hint -- LoopForever for ambient bg use,
    // PlayOnce when they want the intro-frame settle pattern. Slider
    // changes mid-play take effect on the next frame swap
    // (rememberUpdatedState keeps the captured ref fresh without
    // restarting the effect).
    LaunchedEffect(d, loopMode) {
        val totalPlays = when (loopMode) {
            BackgroundLoopMode.LoopForever -> Int.MAX_VALUE
            BackgroundLoopMode.PlayOnce    -> 1
            BackgroundLoopMode.UseCodec    ->
                if (d.repetitionCount < 0) Int.MAX_VALUE else d.repetitionCount + 1
        }
        var played = 0
        while (played < totalPlays) {
            for (i in d.frames.indices) {
                frameIdx = i
                val speed   = speedRef.value.coerceAtLeast(0.01f)
                val waitMs  = (d.durationsMs[i] / speed).toLong().coerceAtLeast(1L)
                delay(waitMs.milliseconds)
            }
            played++
        }
        // PlayOnce / finite codec: hold on the last frame.
        frameIdx = d.frames.lastIndex
    }

    return d.frames[frameIdx.coerceIn(0, d.frames.lastIndex)]
}

private fun decodeBackground(file: File, onPreview: (DecodedBg) -> Unit = {}): DecodedBg? {
    var data:  Data?  = null
    var codec: Codec? = null
    try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        val frameCount = codec.frameCount
        val info       = codec.imageInfo

        // Frame 0 first -- becomes the final result for statics and
        // the preview emit for multi-frame formats so the user sees
        // the image immediately instead of grey while the remaining
        // N-1 frames decode.
        val frame0 = decodeFrame(
            codec, info,
            frame    = 0,
            trackTag = if (frameCount > 1) "BG.animated" else "BG.static",
        )
        val preview = DecodedBg(
            frames          = listOf(frame0),
            durationsMs     = listOf(0),
            repetitionCount = 0,
        )

        if (frameCount <= 1) return preview

        val pixelsTotal = info.width.toLong() * info.height.toLong() * frameCount.toLong()
        if (frameCount > MAX_ANIMATED_FRAMES || pixelsTotal > MAX_ANIMATED_PIXELS_TOTAL) {
            log.warn(
                "Animated bg too large (frames={}, ~{}MB raw) -- using frame 0 only",
                frameCount, pixelsTotal * 4 / 1_048_576,
            )
            return preview
        }

        // Emit the preview so the user sees frame 0 while frames
        // 1..N-1 decode below.
        onPreview(preview)

        val frames    = ArrayList<ImageBitmap>(frameCount).also { it.add(frame0) }
        val durations = ArrayList<Int>(frameCount).also { it.add(codec.framesInfo[0].duration.coerceAtLeast(1)) }
        val infos     = codec.framesInfo
        for (i in 1 until frameCount) {
            frames.add(decodeFrame(codec, info, frame = i, trackTag = "BG.animated"))
            // delay() of 0 spins -- guarantee positive duration per frame.
            durations.add(infos[i].duration.coerceAtLeast(1))
        }
        return DecodedBg(frames, durations, codec.repetitionCount)
    } catch (e: Exception) {
        log.error("Failed to decode custom background at {}", file.absolutePath, e)
        return null
    } finally {
        codec?.close()
        data?.close()
    }
}

private fun decodeFrame(codec: Codec, info: ImageInfo, frame: Int, trackTag: String): ImageBitmap {
    val bmp = org.jetbrains.skia.Bitmap().apply { allocPixels(info) }
    return bmp.use { bmp ->
        codec.readPixels(bmp, frame)
        val img = org.jetbrains.skia.Image.makeFromBitmap(bmp)
        img.use { it.toComposeImageBitmap().also { ib -> SkiaTracker.track(trackTag, ib) } }
    }
}
