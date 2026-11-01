package dev.hivens.nexira

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import kotlin.math.max

private val log = LoggerFactory.getLogger("ArtworkDecoder")

/**
 * Decodes embedded cover art, downscaled so its longest edge is at most [maxEdge].
 * Cover art in the wild runs to several thousand pixels a side and is drawn here at
 * a fraction of that, so the platform decoder is asked for the small size directly
 * instead of producing the large one first.
 *
 * Returns null for an unreadable picture: a track with a broken cover still plays,
 * and the renderer already has a no-artwork branch.
 */
fun decodeArtwork(bytes: ByteArray, maxEdge: Int = MAX_ARTWORK_EDGE): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }.asImageBitmap()
    } catch (e: Exception) {
        log.warn("Failed to decode embedded cover art ({} bytes)", bytes.size, e)
        null
    }
}

/** Longest edge kept for a decoded cover, the same bound the desktop decoder uses. */
const val MAX_ARTWORK_EDGE = 512
