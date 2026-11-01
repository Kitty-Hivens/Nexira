package hivens.ui.background

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.hivens.skinema.libav.VideoDecoder
import hivens.ui.diag.SkinemaGate
import hivens.ui.theme.WallpaperTone
import hivens.ui.theme.luminanceOfArgb
import hivens.ui.theme.wallpaperToneFromImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
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

@Composable
internal actual fun rememberWallpaperPainter(
    file: File,
    settings: BackgroundSettings,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
): Painter? {
    // Classify off the UI thread (the decoders are asked, which is file I/O); null
    // until known, so the background draws nothing for a frame rather than blocking
    // composition on it. Kept in a state keyed on the file rather than in a
    // produceState, which holds the PREVIOUS file's answer until the new one lands
    // -- long enough to open the video player on a still image.
    val resolved = rememberBackgroundMedia(file)
    val mediaKind = resolved?.kind
    // A still is already decoded into a bitmap by here; anything that moves plays
    // through Skinema. Only the active branch composes, so switching media kind
    // tears down the other's decode/player state.
    val staticBitmap = resolved?.bitmap
    // The wallpaper's colours: a still's from the decoded bitmap (off the UI thread), a
    // video's from its first decoded frame.
    var videoColours by remember(file) { mutableStateOf<List<Int>>(emptyList()) }
    val videoPainter = if (mediaKind == BackgroundMediaKind.TimeBased) {
        rememberSkinemaFrame(
            file            = file,
            speedMultiplier = settings.animationSpeedMultiplier,
            loopMode        = settings.loopMode,
            hardwareDecode  = settings.hardwareDecode,
            audio           = settings.audio,
            audioVolume     = settings.audioVolume,
            link            = settings.linkToPlayers,
            onAudioVolume   = onAudioVolume,
            onColours       = { videoColours = it },
        )
    } else {
        null
    }
    // Colours and brightness in ONE pixel read (a large wallpaper is tens of MB, two
    // reads OOM'd). A video hands over only its colours, so its brightness is the luma
    // of the first one.
    val staticTone by produceState<WallpaperTone?>(null, staticBitmap) {
        value = staticBitmap?.let { bmp -> withContext(Dispatchers.Default) { wallpaperToneFromImage(bmp) } }
    }
    val colours = staticTone?.colours ?: videoColours
    val avgLuminance = staticTone?.avgLuminance ?: videoColours.firstOrNull()?.let { luminanceOfArgb(it) }

    // What the wallpaper tells the theme, and nothing else. A frosted surface used to need the
    // whole wallpaper recipe here so it could reproduce the image under itself; it
    // blurs the canvas beneath it now, so the recipe has no second reader and the
    // effect no longer re-fires on every slider tick.
    // Nothing is reported until the picture says something: an empty answer while it
    // decodes would read as "no wallpaper" and drop the theme made from it.
    LaunchedEffect(colours, avgLuminance) {
        if (colours.isEmpty() && avgLuminance == null) return@LaunchedEffect
        onTone(WallpaperTone(colours = colours, avgLuminance = avgLuminance))
    }

    return when {
        staticBitmap != null -> remember(staticBitmap) { BitmapPainter(staticBitmap) }
        else                 -> videoPainter
    }
}

/** What a wallpaper file turned out to be, and its pixels when it is a still. */
private data class ResolvedMedia(val kind: BackgroundMediaKind, val bitmap: ImageBitmap?)

/**
 * Classifies the file and, when it is a still, decodes it -- in ONE pass off the UI
 * thread.
 *
 * These were two effects with a composition round-trip between them: the decode could
 * not start until the probe had returned and been recomposed. So a wallpaper arrived
 * in two visible steps, and every translucent plane above it spent both of them
 * sitting on nothing -- which is what made the blur look like it was applied late,
 * on top of an already-drawn transparency, rather than with the rest of the frame.
 */
@Composable
private fun rememberBackgroundMedia(file: File): ResolvedMedia? {
    // A wallpaper re-decodes on every launch; a full-resolution source pays tens of
    // MB of Skia decode each time. Cache a display-height copy under the shared
    // background-cache dir and decode that on later launches instead.
    val dataDir = koinInject<Path>()
    val cacheDir = remember(dataDir) { dataDir.resolve("background-cache").toFile() }
    val maxHeight = remember { physicalScreenHeight() }
    var resolved by remember(file) { mutableStateOf<ResolvedMedia?>(null) }
    LaunchedEffect(file, maxHeight) {
        if (!file.exists()) return@LaunchedEffect
        resolved = withContext(Dispatchers.IO) {
            val kind = backgroundMediaKind(file)
            ResolvedMedia(
                kind   = kind,
                bitmap = if (kind == BackgroundMediaKind.Static) loadStaticBackground(file, cacheDir, maxHeight) else null,
            )
        }
    }
    return resolved
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

private fun decodeStaticBackground(file: File): ImageBitmap? =
    decodeStill(file)?.use { it.toComposeImageBitmap() }

/**
 * The still's pixels, from whichever decoder reads the file.
 *
 * Skia first, since it is what the cached copies are written for and what every
 * ordinary wallpaper is. What it has no codec for goes to the player's decoder,
 * which reads stills as well as video: a file only reaches this path once
 * [backgroundMediaKind] has established that it holds a single frame, so the
 * decoder is being asked for a picture here, not for a stream.
 */
private fun decodeStill(file: File): Image? = decodeStillWithSkia(file) ?: decodeStillWithDecoder(file)

private fun decodeStillWithSkia(file: File): Image? {
    var data:  Data?  = null
    var codec: Codec? = null
    return try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        Bitmap().apply { allocPixels(codec.imageInfo) }.use { bmp ->
            codec.readPixels(bmp, 0)
            Image.makeFromBitmap(bmp)
        }
    } catch (e: Exception) {
        log.debug("Skia decodes no image in {}, asking the decoder", file.absolutePath, e)
        null
    } finally {
        codec?.close()
        data?.close()
    }
}

/** One frame through the media decoder, straight-alpha RGBA as the player hands it over. */
private fun decodeStillWithDecoder(file: File): Image? {
    if (!SkinemaGate.enabled) return null
    return runCatching {
        VideoDecoder.open(file.toPath()).use { decoder ->
            val frame = decoder.nextFrame() ?: return@use null
            Image.makeRaster(
                ImageInfo(frame.width, frame.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
                frame.rgba,
                frame.width * 4,
            )
        }
    }.getOrElse { e ->
        log.error("Failed to decode static background at {}", file.absolutePath, e)
        null
    }
}

/**
 * Decode [file] at full resolution, box-downscale it to [maxHeight], write the result
 * to [cached] as PNG (best-effort, atomic via a unique temp + move), and return the
 * shrunk bitmap. A source no taller than [maxHeight] is returned as-is and left
 * uncached. [prefix] identifies the source so older cached copies of it are evicted.
 */
private fun decodeAndCacheDownscaled(file: File, cached: File, prefix: String, maxHeight: Int): ImageBitmap? =
    decodeStill(file)?.use { srcImg ->
        if (srcImg.height <= maxHeight) return@use srcImg.toComposeImageBitmap()

        val dh = maxHeight
        val dw = (srcImg.width.toLong() * dh / srcImg.height).toInt().coerceAtLeast(1)
        Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(dw, dh)) }.use { dst ->
            Canvas(dst).use { canvas ->
                canvas.drawImageRect(
                    srcImg,
                    Rect.makeWH(srcImg.width.toFloat(), srcImg.height.toFloat()),
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

