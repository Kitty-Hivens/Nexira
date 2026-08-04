package hivens.ui.background

import dev.hivens.skinema.encode.MediaWriter
import dev.hivens.skinema.encode.VideoEncodeConfig
import dev.hivens.skinema.libav.LibavException
import hivens.ui.diag.SkinemaGate
import dev.hivens.skinema.libav.VideoDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
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
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

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
 * [scope], not the caller's, so navigating away does not abort it. Because it
 * outlives the screen that started it, it is reachable from [optimizing] and
 * stoppable through [cancel] -- an unattended transcode used to hold a core (a
 * software x264 fallback holds several) with nothing on screen to name it and
 * no way to end it short of quitting.
 *
 * Hold this as a process singleton for the same reason: a per-screen instance
 * loses the handle to its own running work the moment the screen goes away.
 */
class BackgroundOptimizer(
    private val cacheDir: Path,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(BackgroundOptimizer::class.java)
    private val inflight = ConcurrentHashMap<String, Deferred<Path>>()

    private val _optimizing = MutableStateFlow<Path?>(null)

    /**
     * The source being transcoded right now, null when nothing is. Lives on the
     * optimizer rather than in the picker's composition so the progress (and its
     * cancel control) is still there after a trip out of the settings screen.
     */
    val optimizing: StateFlow<Path?> = _optimizing.asStateFlow()

    /**
     * A version of [src] no taller than [maxHeight], transcoding and caching it
     * when the source is taller. Returns [src] itself when it already fits, when
     * its size cannot be read, or when no encoder is available -- the caller
     * always gets a usable path. Safe to call for every wallpaper; cheap on a
     * cache hit.
     */
    suspend fun optimize(src: Path, maxHeight: Int): Path {
        if (maxHeight <= 0) return src
        // Off the caller's thread: the picker calls this from the composition
        // scope, which runs on the UI thread, and opening a 4K demuxer there
        // freezes the window for as long as the probe takes.
        val size = withContext(Dispatchers.IO) { probeSize(src) } ?: return src
        val (sw, sh) = size
        if (sh <= maxHeight) return src

        val key = cacheKey(src, maxHeight)
        val dst = cacheDir.resolve("$key.mp4")
        if (isUsable(dst)) return dst

        val deferred = inflight.computeIfAbsent(key) {
            // Published before the coroutine is even scheduled: announcing the
            // work from inside it would leave a window where the picker is idle
            // over a transcode that is already committed to run.
            _optimizing.value = src
            scope.async(Dispatchers.IO) {
                try {
                    if (!isUsable(dst)) transcode(src, dst, sw, sh, maxHeight)
                    if (isUsable(dst)) dst else src
                } finally {
                    inflight.remove(key)
                    _optimizing.compareAndSet(src, null)
                }
            }
        }
        return deferred.await()
    }

    /**
     * Stops whatever is transcoding. The awaiting caller sees a
     * [CancellationException] -- there is no optimized file to hand it, and
     * quietly substituting the oversized source would leave the wallpaper
     * paying the per-frame cost the transcode existed to remove.
     */
    fun cancel() {
        inflight.values.forEach { it.cancel() }
    }

    /**
     * Drops cached transcodes other than [keep], off the caller's thread (both
     * call sites are UI event handlers). A transcode in flight suspends the
     * sweep -- its output is nobody's wallpaper yet, and the next completion
     * sweeps anyway.
     */
    fun evictUnused(keep: Path?) {
        if (inflight.isNotEmpty()) return
        scope.launch(Dispatchers.IO) { sweepTranscodes(cacheDir, keep) }
    }

    private fun probeSize(src: Path): Pair<Int, Int>? {
        // Skinema disabled -> report unknown size; optimize() then returns the
        // source unchanged and never reaches the native transcode.
        if (!SkinemaGate.enabled) return null
        return runCatching {
            VideoDecoder.open(src).use { it.videoSize() }
        }.getOrNull()
    }

    /**
     * Decode -> box-downscale -> encode into [dst] (atomically via a .part file).
     * Cancellable per frame: the loop is the whole cost of the operation, so a
     * cancellation that only landed between encoders would still run a full pass
     * on a file nobody wants any more.
     */
    private suspend fun transcode(src: Path, dst: Path, sw: Int, sh: Int, maxHeight: Int) {
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
                            coroutineContext.ensureActive()
                            val frame = dec.nextFrame() ?: break
                            writer.writeFrame(scaleRgba(frame.rgba, frame.width, frame.height, dw, dh), frame.ptsNanos)
                        }
                        writer.finish()
                    }
                }
                Files.move(part, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                log.info("Optimized wallpaper {} -> {}x{} via {}", src.fileName, dw, dh, encoder)
                return
            } catch (e: CancellationException) {
                runCatching { Files.deleteIfExists(part) }
                log.info("Wallpaper transcode of {} cancelled", src.fileName)
                throw e
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
 * Deletes every cached transcode in [cacheDir] except [keep]. A video wallpaper's
 * image path points INTO this cache, so any other .mp4 here is the transcode of a
 * wallpaper that is no longer set and nothing will ever read it again -- without
 * this every video the user tries leaves a full copy on disk forever. A .part file
 * belongs to a transcode still running and is left alone, as is the still-image
 * cache, which evicts per source on its own.
 */
internal fun sweepTranscodes(cacheDir: Path, keep: Path?) {
    val keepName = keep?.fileName?.toString()
    runCatching {
        cacheDir.toFile()
            .listFiles { f -> f.isFile && f.name.endsWith(".mp4") && !f.name.endsWith(".part.mp4") }
            ?.forEach { f -> if (f.name != keepName) f.delete() }
    }
}

/**
 * The tallest physical-pixel height across all monitors. AWT's `screenSize` is
 * logical points, so on a HiDPI / scaled display (mac Retina, Windows display
 * scaling, fractional XWayland) it under-reports and a wallpaper downscaled to it
 * renders soft once the framebuffer upscales it back; the display transform's scale
 * factor recovers the true pixel height. 0 when headless -- callers then skip the
 * downscale and decode the source directly.
 */
internal fun physicalScreenHeight(): Int = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.maxOf { dev ->
        val gc = dev.defaultConfiguration
        (gc.bounds.height * gc.defaultTransform.scaleY).toInt()
    }
}.getOrDefault(0)

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
