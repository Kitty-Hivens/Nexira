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
import hivens.ui.theme.WallpaperTone
import hivens.ui.theme.luminanceOfArgb
import hivens.ui.theme.wallpaperToneFromImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.makeFromFileName
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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

    // Classify off the UI thread (png/webp need a frame-count probe); null until
    // known, so the background draws nothing for a frame rather than blocking
    // composition on file I/O. Kept in a state keyed on the file rather than in a
    // produceState, which holds the PREVIOUS file's answer until the new one lands
    // -- long enough to open the video player on a still image.
    var mediaKind by remember(file) { mutableStateOf<BackgroundMediaKind?>(null) }
    LaunchedEffect(file) { mediaKind = withContext(Dispatchers.IO) { backgroundMediaKind(file) } }

    // Still image decodes through Skia; video + animated images play through
    // Skinema. Only the active branch composes, so switching media kind tears
    // down the other's decode/player state.
    val staticBitmap = if (mediaKind == BackgroundMediaKind.Static) rememberStaticImage(file) else null
    // Material-You seed: static from the decoded bitmap (off-thread); video from its
    // first decoded frame (via the player's onSeed). Either feeds BackdropState.seedArgb.
    var videoSeed by remember(file) { mutableStateOf<Int?>(null) }
    val videoPainter = if (mediaKind == BackgroundMediaKind.TimeBased)
        rememberSkinemaFrame(file, settings.animationSpeedMultiplier, settings.loopMode, settings.hardwareDecode, onSeed = { videoSeed = it }) else null
    // Seed + brightness in ONE pixel read (a large wallpaper is tens of MB; two reads
    // OOM'd). Video only exposes its seed, so brightness falls back to the seed's luma.
    val staticTone by produceState<WallpaperTone?>(null, staticBitmap) {
        value = staticBitmap?.let { bmp -> withContext(Dispatchers.Default) { wallpaperToneFromImage(bmp) } }
    }
    val seedArgb = staticTone?.seedArgb ?: videoSeed
    val avgLuminance = staticTone?.avgLuminance ?: videoSeed?.let { luminanceOfArgb(it) }

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
    // Saturation applies at the Image, so it covers the static painter and every
    // video frame alike; the frost slice mirrors it via BackdropState.saturation.
    val saturationFilter = remember(settings.saturation) { bgSaturationFilter(settings.saturation) }
    // Publish the wallpaper recipe for frosted surfaces. A still carries its
    // bitmap so the frost redraws a real blurred slice; time-based publishes a
    // null bitmap + isAnimated so the frost falls back to a scrim (per-frame
    // reblur of video is too costly). Live parallax is read through
    // mousePosProvider so mouse movement does not churn this.
    LaunchedEffect(staticBitmap, settings, isAnimated, seedArgb, avgLuminance) {
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
                saturation        = settings.saturation,
                parallaxIntensity = settings.parallaxIntensity,
                isAnimated        = isAnimated,
                seedArgb          = seedArgb,
                avgLuminance      = avgLuminance,
                mouse             = mousePosProvider,
            ),
        )
    }

    val useParallax = settings.parallaxIntensity > 0f
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

    if (useParallax) {
        val target = parallaxTranslationFor(mousePosProvider(), settings.parallaxIntensity)
        val parallaxX by animateFloatAsState(target.x, spring(stiffness = 50f, dampingRatio = 0.8f))
        val parallaxY by animateFloatAsState(target.y, spring(stiffness = 50f, dampingRatio = 0.8f))
        Image(
            painter            = painter,
            contentDescription = null,
            contentScale       = contentScale,
            alignment          = alignment,
            colorFilter        = saturationFilter,
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
            colorFilter        = saturationFilter,
            modifier           = baseModifier
        )
    }
}

@Composable
private fun rememberStaticImage(file: File): ImageBitmap? {
    // A wallpaper re-decodes on every launch; a full-resolution source pays tens of
    // MB of Skia decode each time. Cache a display-height copy under the shared
    // background-cache dir and decode that on later launches instead.
    val dataDir = koinInject<Path>()
    val cacheDir = remember(dataDir) { dataDir.resolve("background-cache").toFile() }
    val maxHeight = remember { physicalScreenHeight() }
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, maxHeight) {
        if (!file.exists()) return@LaunchedEffect
        withContext(Dispatchers.IO) { bitmap = loadStaticBackground(file, cacheDir, maxHeight) }
    }
    return bitmap
}

/**
 * Decode a still wallpaper, downscaling an oversized source to [maxHeight] once and
 * caching the shrunk copy keyed on (path, mtime, height). Later launches decode that
 * small file; a source already within the display height decodes directly, uncached.
 * The path is hashed into a stable prefix so a re-edit or a display change evicts the
 * source's earlier cached copy rather than accumulating orphans.
 */
internal fun loadStaticBackground(file: File, cacheDir: File, maxHeight: Int): ImageBitmap? {
    if (maxHeight <= 0) return decodeStaticBackground(file)
    val prefix = "img-${sha(file.absolutePath).take(16)}-"
    val cached = File(cacheDir, "$prefix${sha("${file.lastModified()}:h$maxHeight").take(16)}.png")
    if (cached.length() > 0L) {
        decodeStaticBackground(cached)?.let { return it }
    }
    return decodeAndCacheDownscaled(file, cached, prefix, maxHeight)
}

private fun sha(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(s.toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }
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

/**
 * Decode [file] at full resolution, box-downscale it to [maxHeight], write the result
 * to [cached] as PNG (best-effort, atomic via a unique temp + move), and return the
 * shrunk bitmap. A source no taller than [maxHeight] is returned as-is and left
 * uncached. [prefix] identifies the source so older cached copies of it are evicted.
 */
private fun decodeAndCacheDownscaled(file: File, cached: File, prefix: String, maxHeight: Int): ImageBitmap? {
    var data:  Data?  = null
    var codec: Codec? = null
    return try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        val info = codec.imageInfo
        if (info.height <= maxHeight) return decodeFrame(codec, info, frame = 0)

        val dh  = maxHeight
        val dw  = (info.width.toLong() * dh / info.height).toInt().coerceAtLeast(1)
        val src = Bitmap().apply { allocPixels(info) }
        src.use {
            codec.readPixels(src, 0)
            val dst = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(dw, dh)) }
            dst.use {
                Image.makeFromBitmap(src).use { srcImg ->
                    Canvas(dst).drawImageRect(
                        srcImg,
                        Rect.makeWH(info.width.toFloat(), info.height.toFloat()),
                        Rect.makeWH(dw.toFloat(), dh.toFloat()),
                        SamplingMode.LINEAR, null, true,
                    )
                }
                Image.makeFromBitmap(dst).use { dstImg ->
                    writePngCache(dstImg, cached, prefix)
                    dstImg.toComposeImageBitmap()
                }
            }
        }
    } catch (e: Exception) {
        log.error("Failed to decode static background at {}", file.absolutePath, e)
        null
    } finally {
        codec?.close()
        data?.close()
    }
}

private fun writePngCache(image: Image, dst: File, prefix: String) {
    runCatching {
        val encoded = image.encodeToData(EncodedImageFormat.PNG) ?: return
        encoded.use { enc ->
            val dir = dst.parentFile ?: return
            dir.mkdirs()
            // Unique temp per writer so two live instances of the same wallpaper never
            // tear a shared .part; atomic move publishes it.
            val part = Files.createTempFile(dir.toPath(), "${dst.name}.", ".part")
            Files.write(part, enc.bytes)
            Files.move(part, dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Evict the source's older display-height copies (one live file per source).
            dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".png") && f.name != dst.name }
                ?.forEach { it.delete() }
        }
    }
}

private fun decodeFrame(codec: Codec, info: ImageInfo, frame: Int): ImageBitmap {
    val bmp = Bitmap().apply { allocPixels(info) }
    return bmp.use { bmp ->
        codec.readPixels(bmp, frame)
        Image.makeFromBitmap(bmp).use { it.toComposeImageBitmap() }
    }
}
