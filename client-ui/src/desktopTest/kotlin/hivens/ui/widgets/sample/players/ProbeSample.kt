package hivens.ui.widgets.sample.players

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.File

/**
 * The artwork a player render probe draws with.
 *
 * A sibling of the one in nx-ui rather than a shared one, because the two test
 * source sets do not see each other and a module dependency added to share
 * forty lines of test helper is a worse trade than the duplication.
 *
 * Probes used to name an absolute path on one developer's machine, which made
 * them unrunnable anywhere else and put that path in the repository. The sample
 * stays out of the tree on purpose: it is a real commercial release, and a
 * picture nobody holds the rights to does not belong in a source repository to
 * make a developer tool prettier. So the path is named from outside, and a probe
 * run without one draws a generated stand-in rather than failing.
 *
 * ```
 * ./gradlew :client-ui:desktopTest -Dnexira.probe.cover=/path/to/cover.jpg
 * ```
 */
internal object ProbeSample {

    private const val COVER_PROPERTY = "nexira.probe.cover"

    /** The configured cover, or a generated stand-in. Never null, so a probe never branches. */
    fun cover(): ImageBitmap = load() ?: placeholder()

    private fun load(): ImageBitmap? {
        val path = System.getProperty(COVER_PROPERTY) ?: System.getenv("NEXIRA_PROBE_COVER")
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
        return runCatching { SkImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
    }

    /**
     * A stand-in that cannot be mistaken for artwork.
     *
     * Deliberately blocky and off-palette: a sheet reviewed with a plausible
     * picture on it is a sheet where nobody notices the sample was missing, and
     * one of these probes exists to show what a real cover does to the colour of
     * the card around it.
     */
    private fun placeholder(side: Int = 512): ImageBitmap {
        val bitmap = Bitmap().apply { allocN32Pixels(side, side) }
        val canvas = Canvas(bitmap)
        canvas.clear(0xFF3B2F5C.toInt())
        val paint = Paint().apply { color = 0xFF5A4A8A.toInt() }
        val step = side / 8f
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                if ((row + column) % 2 != 0) continue
                canvas.drawRect(Rect.makeXYWH(column * step, row * step, step, step), paint)
            }
        }
        return SkImage.makeFromBitmap(bitmap).toComposeImageBitmap()
    }
}
