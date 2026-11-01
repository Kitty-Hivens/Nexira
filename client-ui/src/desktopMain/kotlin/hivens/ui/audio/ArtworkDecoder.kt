package hivens.ui.audio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ArtworkDecoder")

/**
 * Decodes embedded cover art, downscaled so its longest edge is at most
 * [maxEdge]. Cover art in the wild runs to several thousand pixels a side and
 * is rendered here at a fraction of that; keeping the source resolution would
 * hold tens of megabytes of native image per track for no visible gain.
 *
 * Returns null for an unreadable picture -- a track with a broken cover still
 * plays, and the renderer already has a no-artwork branch.
 */
internal fun decodeArtwork(bytes: ByteArray, maxEdge: Int = MAX_ARTWORK_EDGE): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        Image.makeFromEncoded(bytes).use { src ->
            val longest = maxOf(src.width, src.height)
            if (longest <= maxEdge) return@use src.toComposeImageBitmap()

            val scale = maxEdge.toFloat() / longest
            val dw = (src.width * scale).toInt().coerceAtLeast(1)
            val dh = (src.height * scale).toInt().coerceAtLeast(1)
            Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(dw, dh)) }.use { dst ->
                Canvas(dst).use { canvas ->
                    canvas.drawImageRect(
                        src,
                        Rect.makeWH(src.width.toFloat(), src.height.toFloat()),
                        Rect.makeWH(dw.toFloat(), dh.toFloat()),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                }
                Image.makeFromBitmap(dst).use { it.toComposeImageBitmap() }
            }
        }
    } catch (e: Exception) {
        log.warn("Failed to decode embedded cover art ({} bytes)", bytes.size, e)
        null
    }
}

/**
 * Longest edge kept for a decoded cover. Sized for a full now-playing panel on a
 * HiDPI display, which is well past the 52dp thumbnail the player shows today.
 */
internal const val MAX_ARTWORK_EDGE = 512
