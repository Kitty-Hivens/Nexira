package hivens.ui.nx

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.File

/**
 * The artwork a render probe draws with.
 *
 * Probes used to name an absolute path on one developer's machine, which made
 * them unrunnable anywhere else: the guarded ones quietly produced a sheet with
 * no cover, and the unguarded ones threw. A path like that also has no business
 * in a repository, and it went into one.
 *
 * The sample itself stays out of the tree deliberately. It is a real commercial
 * release, and a picture nobody holds the rights to does not belong in a source
 * repository to make a developer tool prettier. So the path is named from
 * outside, and a probe run without one draws a generated stand-in instead of
 * failing.
 *
 * ```
 * ./gradlew :nx-ui:desktopTest -Dnexira.probe.cover=/path/to/cover.jpg
 * ```
 */
internal object ProbeSample {

    private const val COVER_PROPERTY = "nexira.probe.cover"
    private const val BLUR_PROPERTY = "nexira.probe.cover.blur"

    /** The configured cover, or a generated stand-in. Never null, so a probe never branches. */
    fun cover(): ImageBitmap = load(COVER_PROPERTY) ?: placeholder(dark = false)

    /**
     * The blurred companion, or a generated one. Falls back to the sharp sample
     * before the stand-in: a probe that wanted a blur can live with the same
     * picture, and it cannot live with nothing.
     */
    fun coverBlur(): ImageBitmap = load(BLUR_PROPERTY) ?: load(COVER_PROPERTY) ?: placeholder(dark = true)

    /** The raw bytes, for a probe that resamples or re-encodes rather than draws. */
    fun coverImage(): SkImage = loadImage(COVER_PROPERTY) ?: placeholderImage(dark = false)

    private fun load(property: String): ImageBitmap? = loadImage(property)?.toComposeImageBitmap()

    private fun loadImage(property: String): SkImage? {
        val path = System.getProperty(property) ?: System.getenv(property.replace('.', '_').uppercase())
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
        return runCatching { SkImage.makeFromEncoded(file.readBytes()) }.getOrNull()
    }

    /**
     * A stand-in that cannot be mistaken for artwork.
     *
     * Deliberately blocky and off-palette: a sheet reviewed with a plausible
     * picture on it is a sheet where nobody notices the sample was missing, and
     * the point of several of these probes is what a real cover does to the
     * colour around it.
     */
    private fun placeholder(dark: Boolean): ImageBitmap = placeholderImage(dark).toComposeImageBitmap()

    private fun placeholderImage(dark: Boolean, side: Int = 512): SkImage {
        val bitmap = Bitmap().apply { allocN32Pixels(side, side) }
        val canvas = Canvas(bitmap)
        val ground = if (dark) 0xFF1B1B22.toInt() else 0xFF3B2F5C.toInt()
        canvas.clear(ground)
        val paint = Paint()
        val step = side / 8f
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                if ((row + column) % 2 != 0) continue
                paint.color = if (dark) 0xFF2C2C38.toInt() else 0xFF5A4A8A.toInt()
                canvas.drawRect(
                    Rect.makeXYWH(column * step, row * step, step, step),
                    paint,
                )
            }
        }
        return SkImage.makeFromBitmap(bitmap)
    }
}
