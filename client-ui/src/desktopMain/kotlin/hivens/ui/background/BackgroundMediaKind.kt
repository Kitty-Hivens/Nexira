package hivens.ui.background

import dev.hivens.skinema.libav.VideoDecoder
import hivens.ui.diag.SkinemaGate
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
 * The question goes to the decoders rather than to the file name, and it is one
 * question: does the picture move. Skia answers it for what it can open, by
 * saying how many frames are in the file, so an animated PNG or WebP reaches
 * the player and a GIF holding one frame does not.
 *
 * What Skia will not open is put to the player's decoder, which reads stills as
 * readily as it reads video: tiff, tga, dpx, exr and the rest of the formats
 * Skia carries no codec for arrive through the same demuxers. A second frame
 * means it moves. A single frame means a still Skia could not read, and it
 * belongs on the still path even though the player is what decoded it, because
 * the still path downscales it once and caches the result where the player
 * would hold a decode thread for a picture that never changes and offer the
 * file to the transcoder as though it were a video.
 *
 * An extension list used to answer all of this, and everything outside it fell
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
    stillFrameCount(file)?.let { frames ->
        return if (frames > 1) BackgroundMediaKind.TimeBased else BackgroundMediaKind.Static
    }
    return if (movesUnderTheDecoder(file)) BackgroundMediaKind.TimeBased else BackgroundMediaKind.Static
}

/**
 * Whether the player's decoder finds a second frame in [file]. False for a
 * still, and false for a file nothing reads at all, which then reaches the
 * still path and is reported there rather than opening a player that could only
 * fail later.
 *
 * Neither frame is converted to RGBA. This asks how many there are; the pixels
 * are decoded again by whichever path the answer chooses, and converting here
 * would be a full frame of work thrown away on every wallpaper that turns out
 * to be a video.
 */
private fun movesUnderTheDecoder(file: File): Boolean {
    if (!SkinemaGate.enabled) return false
    return runCatching {
        VideoDecoder.open(file.toPath()).use { decoder ->
            decoder.nextFrame(convert = false) != null && decoder.nextFrame(convert = false) != null
        }
    }.getOrElse { e ->
        log.debug("No decoder reads {}, leaving it to the still path", file.absolutePath, e)
        false
    }
}

/**
 * How many frames Skia finds in [file], or null when it will not open the file
 * as an image at all. A header read rather than a decode.
 */
private fun stillFrameCount(file: File): Int? {
    var data:  Data?  = null
    var codec: Codec? = null
    return try {
        data  = Data.makeFromFileName(file.absolutePath)
        codec = Codec.makeFromData(data)
        codec.frameCount
    } catch (e: Exception) {
        log.debug("Skia reads no image in {}, asking the decoder", file.absolutePath, e)
        null
    } finally {
        codec?.close()
        data?.close()
    }
}
