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
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

private val log = LoggerFactory.getLogger("TrackInfo")

/**
 * What a track says about itself: the container's own tags and the picture it
 * carries. Read once per file, so a widget can render a track by name rather
 * than by path.
 *
 * [title] always has something to show -- the file name stands in for a file
 * with no tags, which is most of a game launcher's music folder. The rest is
 * genuinely optional and a renderer is expected to leave the line out rather
 * than print "Unknown artist" at the user.
 */
data class TrackInfo(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ImageBitmap? = null,
)

/**
 * Reads [tags] into a [TrackInfo], falling back to [file]'s name for the title.
 *
 * Tag keys are whatever the container calls them: libav lowercases ID3 frames
 * and MP4 atoms, passes Vorbis comments through in the case the encoder wrote
 * (usually upper), and the same field arrives under different names across
 * formats -- so the lookup is case-insensitive over a list of aliases per field.
 * A tag that exists but is blank counts as absent; players write empty frames.
 */
internal fun trackInfoFrom(tags: Map<String, String>, file: Path, artwork: ImageBitmap? = null): TrackInfo {
    val byLowerKey = tags.entries.associate { (key, value) -> key.lowercase(Locale.ROOT) to value }
    fun tag(vararg aliases: String): String? =
        aliases.firstNotNullOfOrNull { alias -> byLowerKey[alias]?.trim()?.takeIf { it.isNotEmpty() } }

    return TrackInfo(
        title   = tag("title", "track_title") ?: fileTitle(file),
        artist  = tag("artist", "album_artist", "albumartist", "performer", "author"),
        album   = tag("album"),
        artwork = artwork,
    )
}

/** The file name without its extension, or the whole name when it has none. */
private fun fileTitle(file: Path): String =
    file.nameWithoutExtension.takeIf { it.isNotBlank() } ?: file.name

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
