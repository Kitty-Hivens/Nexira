package hivens.ui.background

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.makeFromFileName
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

private val log = LoggerFactory.getLogger("BackgroundMediaKind")

/** How a background source is drawn: a still image, or time-based media. */
enum class BackgroundMediaKind { Static, TimeBased }

// Unambiguous by extension.
private val STATIC_EXTENSIONS    = setOf("jpg", "jpeg", "bmp")
private val TIMEBASED_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "ogv", "gif")

// A .png may be APNG and a .webp may be animated, so the extension alone does
// not decide -- the frame count does.
private val PROBE_EXTENSIONS     = setOf("png", "apng", "webp")

/**
 * Classifies a background file as a still ([Static]) or time-based media
 * ([TimeBased] -- video or animated image). Time-based plays through Skinema;
 * static stays on the Image path.
 *
 * Unambiguous extensions answer without touching the file. png/apng/webp are
 * probed for frame count through a Skia [Codec] (a header read, not a full
 * decode) so an animated PNG/WebP is routed to the player rather than frozen
 * on frame 0.
 */
fun backgroundMediaKind(file: File): BackgroundMediaKind {
    val ext = file.extension.lowercase(Locale.ROOT)
    return when {
        ext in TIMEBASED_EXTENSIONS -> BackgroundMediaKind.TimeBased
        ext in STATIC_EXTENSIONS    -> BackgroundMediaKind.Static
        ext in PROBE_EXTENSIONS     ->
            if (isMultiFrame(file)) BackgroundMediaKind.TimeBased else BackgroundMediaKind.Static
        else                        -> BackgroundMediaKind.Static
    }
}

private fun isMultiFrame(file: File): Boolean {
    if (!file.exists()) return false
    var data:  Data?  = null
    var codec: Codec? = null
    return try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        codec.frameCount > 1
    } catch (e: Exception) {
        // Unparseable header: treat as static and let the still path surface
        // its own error rather than spinning up the player on a dead file.
        log.warn("Frame-count probe failed for {} -- treating as static", file.absolutePath, e)
        false
    } finally {
        codec?.close()
        data?.close()
    }
}
