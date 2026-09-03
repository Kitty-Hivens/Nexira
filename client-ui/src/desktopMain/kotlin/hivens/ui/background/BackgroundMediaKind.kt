package hivens.ui.background

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.makeFromFileName
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("BackgroundMediaKind")

/** How a background source is drawn: a still image, or time-based media. */
enum class BackgroundMediaKind { Static, TimeBased }

/**
 * Classifies a background file as a still ([Static]) or as time-based media
 * ([TimeBased], a video or an animated image). A still stays on the Image path,
 * time-based plays through Skinema.
 *
 * The question goes to the decoders rather than to the file name. Skia is the
 * authority on what the still path can draw: it opens the file and says how
 * many frames are in it, so an animated PNG or WebP reaches the player and a
 * GIF holding one frame does not. What Skia will not open at all is not a still
 * this can draw, so it goes to the player, whose bundle carries every decoder
 * and demuxer FFmpeg builds without an external library.
 *
 * An extension list used to answer instead, and everything outside it fell
 * through to [Static]: a wallpaper in a container the player reads perfectly
 * well was handed to the image path, which drew nothing and logged a decode
 * failure. The list is gone rather than lengthened, because the set it was
 * standing in for is the one the natives bundle decides, not one this file can
 * keep up with.
 */
fun backgroundMediaKind(file: File): BackgroundMediaKind {
    // A file that is not there is nobody's to classify. The still path already
    // reports the absence; opening a player on it would reach the same answer
    // later, and hold a decode thread on the way.
    if (!file.exists()) return BackgroundMediaKind.Static
    val frames = stillFrameCount(file) ?: return BackgroundMediaKind.TimeBased
    return if (frames > 1) BackgroundMediaKind.TimeBased else BackgroundMediaKind.Static
}

/**
 * How many frames the still path finds in [file], or null when Skia will not
 * open it as an image at all. A header read rather than a decode.
 */
private fun stillFrameCount(file: File): Int? {
    var data:  Data?  = null
    var codec: Codec? = null
    return try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        codec.frameCount
    } catch (e: Exception) {
        log.debug("Not a still image, handing {} to the player", file.absolutePath, e)
        null
    } finally {
        codec?.close()
        data?.close()
    }
}
