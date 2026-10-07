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
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
        aliases.firstNotNullOfOrNull { alias ->
            byLowerKey[alias]?.trim()?.takeIf { it.isNotEmpty() }?.let(::repairMojibake)
        }

    return TrackInfo(
        title   = tag("title", "track_title") ?: fileTitle(file),
        artist  = tag("artist", "album_artist", "albumartist", "performer", "author"),
        album   = tag("album"),
        artwork = artwork,
    )
}

/**
 * Best-effort repair of a tag string a decoder read as ISO-8859-1 when its bytes
 * were really a legacy CJK encoding, the mojibake a lot of ID3 tags carry.
 *
 * The bytes are lossless to recover, since Latin-1 is a 1:1 byte-to-codepoint map,
 * so the string is re-encoded to those bytes and tried against the encodings a
 * mislabelled tag is most often written in. A candidate is accepted only when it
 * both carries a CJK glyph and has shed the Latin-1 supplement bytes that flagged
 * the string, so a wrong guess that merely re-scrambles the bytes is rejected.
 *
 * Two gates keep it off legitimate text. A string with any character above U+00FF
 * is genuine Unicode a decoder already got right and is returned untouched, and a
 * string that is mostly ASCII is ordinary accented Western text (Cafe, Motorhead)
 * rather than the near-all-high-byte run a CJK glyph turns into. It cannot recover
 * a byte the tag itself lost: a character replaced by '?' in the file stays a '?'.
 */
internal fun repairMojibake(s: String): String {
    if (s.isEmpty() || s.any { it.code > 0xFF }) return s
    val high = s.count { it.code in 0x80..0xFF }
    if (high * 2 < s.length) return s
    val bytes = s.toByteArray(Charsets.ISO_8859_1)
    for (charset in MOJIBAKE_CANDIDATES) {
        val decoded = decodeStrict(bytes, charset) ?: continue
        if (looksRepaired(decoded)) return decoded
    }
    return s
}

/** A strict decode, or null when the bytes are not valid in [charset]. */
private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/** Whether a candidate decode is the repair rather than more noise. */
private fun looksRepaired(s: String): Boolean =
    s.any { isCjk(it.code) } && s.none { it.code in 0x80..0xFF }

private fun isCjk(code: Int): Boolean =
    code in 0x3040..0x30FF ||   // hiragana and katakana
    code in 0x3400..0x4DBF ||   // CJK extension A
    code in 0x4E00..0x9FFF ||   // CJK unified ideographs
    code in 0xF900..0xFAFF ||   // CJK compatibility ideographs
    code in 0xFF00..0xFFEF      // fullwidth and halfwidth forms

/**
 * The encodings a mislabelled tag is tried against, in order of how much their own
 * structure vouches for a clean decode: UTF-8 rejects almost everything that is not
 * UTF-8, Shift_JIS is stricter than GBK, and GBK is the permissive last resort a
 * Chinese-locale tagger's Japanese title lands in. Absent from a runtime built
 * without jdk.charsets an entry is skipped rather than fatal.
 */
private val MOJIBAKE_CANDIDATES: List<Charset> = listOfNotNull(
    Charsets.UTF_8,
    runCatching { Charset.forName("Shift_JIS") }.getOrNull(),
    runCatching { Charset.forName("GBK") }.getOrNull(),
)

/**
 * The file name without its extension, or the whole name when it has none.
 *
 * Shared rather than private because it is the name a file shows under, and a
 * renderer standing in for absent tags has to reach for the same one: two
 * fallbacks that differ by an extension make a track rename itself the moment
 * the tags arrive or the playback fails.
 */
internal fun fileTitle(file: Path): String =
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
