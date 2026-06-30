package hivens.ui.background

import dev.hivens.skinema.encode.MediaWriter
import dev.hivens.skinema.encode.VideoEncodeConfig
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downscales an oversized video wallpaper to the display height ONCE, caching
 * the result, so playback decodes/converts/uploads a display-resolution frame
 * instead of a 4K one every frame -- the wallpaper's per-frame cost is the
 * source resolution, not the monitor's, and a 4K source on a 2K screen pays the
 * full 4K tax (decode + RGBA convert + a ~33 MB texture upload per frame) for a
 * frame the GPU then shrinks anyway. Transcoding to display height once makes
 * every later frame ~4x cheaper.
 *
 * Uses Skinema end to end: [VideoDecoder] reads source frames, each is box-
 * averaged down, [MediaWriter] re-encodes them. The encoder is the GPU
 * h264_vaapi when the loaded natives carry it (the decode tier does, on Linux),
 * else software libx264; with neither the source is returned unchanged, so a
 * bundle without an encoder degrades to the old behaviour rather than failing.
 *
 * One transcode per (source, mtime, height) key at a time; the work runs in
 * [scope], not the caller's, so navigating away does not abort it.
 */
class BackgroundOptimizer(
    private val cacheDir: Path,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(BackgroundOptimizer::class.java)
    private val inflight = ConcurrentHashMap<String, Deferred<Path>>()

    /**
     * A version of [src] no taller than [maxHeight], transcoding and caching it
     * when the source is taller. Returns [src] itself when it already fits, when
     * its size cannot be read, or when no encoder is available -- the caller
     * always gets a usable path. Safe to call for every wallpaper; cheap on a
     * cache hit.
     */
    suspend fun optimize(src: Path, maxHeight: Int): Path {
        if (maxHeight <= 0) return src
        val size = probeSize(src) ?: return src
        val (sw, sh) = size
        if (sh <= maxHeight) return src

        val key = cacheKey(src, maxHeight)
        val dst = cacheDir.resolve("$key.mp4")
        if (isUsable(dst)) return dst

        val deferred = inflight.computeIfAbsent(key) {
            scope.async(Dispatchers.IO) {
                try {
                    if (!isUsable(dst)) transcode(src, dst, sw, sh, maxHeight)
                    if (isUsable(dst)) dst else src
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return deferred.await()
    }

    private fun probeSize(src: Path): Pair<Int, Int>? = runCatching {
        VideoDecoder.open(src).use { it.videoSize() }
    }.getOrNull()

    /** Decode -> box-downscale -> encode into [dst] (atomically via a .part file). */
    private fun transcode(src: Path, dst: Path, sw: Int, sh: Int, maxHeight: Int) {
        Files.createDirectories(cacheDir)
        val dh = maxHeight.let { it - (it % 2) }
        val dw = ((sw.toLong() * dh / sh).toInt()).let { it - (it % 2) }.coerceAtLeast(2)
        // The temp file keeps the .mp4 suffix: avformat picks the muxer from the
        // extension, and a bare ".part" leaves it with none -> open fails.
        val part = dst.resolveSibling(dst.fileName.toString().removeSuffix(".mp4") + ".part.mp4")

        for (encoder in VIDEO_ENCODERS) {
            try {
                VideoDecoder.open(src).use { dec ->
                    MediaWriter.open(part, VideoEncodeConfig(encoder, dw, dh, TRANSCODE_FPS)).use { writer ->
                        while (true) {
                            val frame = dec.nextFrame() ?: break
                            writer.writeFrame(scaleRgba(frame.rgba, frame.width, frame.height, dw, dh), frame.ptsNanos)
                        }
                        writer.finish()
                    }
                }
                Files.move(part, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                log.info("Optimized wallpaper {} -> {}x{} via {}", src.fileName, dw, dh, encoder)
                return
            } catch (e: LibavException) {
                runCatching { Files.deleteIfExists(part) }
                log.debug("Encoder {} unavailable or failed for {}, trying next", encoder, src.fileName, e)
            }
        }
        log.warn("No usable encoder to downscale {}; playing the source as-is", src.fileName)
    }

    private fun isUsable(p: Path): Boolean = runCatching { Files.size(p) > 0L }.getOrDefault(false)

    private fun cacheKey(src: Path, maxHeight: Int): String {
        val mtime = runCatching { Files.getLastModifiedTime(src).toMillis() }.getOrDefault(0L)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(src.toAbsolutePath().toString().toByteArray())
        md.update(mtime.toString().toByteArray())
        md.update("h$maxHeight".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        // GPU VAAPI first (decode tier ships it on Linux, fast), then software.
        private val VIDEO_ENCODERS = listOf("h264_vaapi", "libx264")

        // A rate-control hint only -- per-frame pts (microsecond, VFR) carry the
        // real timing, so the source cadence is preserved regardless.
        private const val TRANSCODE_FPS = 30
    }
}

/**
 * Downscale a tightly packed RGBA8888 buffer ([sw]x[sh] -> [dw]x[dh]) with
 * Skia's resampler -- SIMD C++, far faster than a per-pixel Kotlin loop, which
 * is the slow half of a 4K transcode. Video frames are opaque, so the
 * straight/premultiplied alpha distinction does not bite here.
 */
internal fun scaleRgba(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
    if (sw == dw && sh == dh) return src
    val srcImage = Image.makeRaster(ImageInfo(sw, sh, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), src, sw * 4)
    val dst = Bitmap().apply { allocPixels(ImageInfo(dw, dh, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)) }
    try {
        Canvas(dst).drawImageRect(
            srcImage,
            Rect.makeWH(sw.toFloat(), sh.toFloat()),
            Rect.makeWH(dw.toFloat(), dh.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        return dst.readPixels() ?: ByteArray(dw * dh * 4)
    } finally {
        srcImage.close()
        dst.close()
    }
}
