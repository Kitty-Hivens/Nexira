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
     * A stand-in that cannot be mistaken for artwork, and survives being blurred.
     *
     * Deliberately blocky and off-palette: a sheet reviewed with a plausible
     * picture on it is a sheet where nobody notices the sample was missing, and
     * one of these probes exists to show what a real cover does to the colour of
     * the card around it.
     *
     * Large regions rather than the fine checkerboard this used to be. A card
     * that blurs its cover into a ground turns an eight by eight check into one
     * flat wash, so the sheet came out looking correct while proving nothing
     * about the blur or about the light it is supposed to give the text. Four
     * quadrants of clashing colour and a disc across them blur into something
     * with a top and a bottom, which is what such a card actually has to cope
     * with.
     */
    private fun placeholder(side: Int = 512): ImageBitmap {
        val bitmap = Bitmap().apply { allocN32Pixels(side, side) }
        val canvas = Canvas(bitmap)
        canvas.clear(0xFF1B2440.toInt())
        val half = side / 2f
        val paint = Paint()
        paint.color = 0xFFB03A2E.toInt()
        canvas.drawRect(Rect.makeXYWH(0f, 0f, half, half), paint)
        paint.color = 0xFF2E8B57.toInt()
        canvas.drawRect(Rect.makeXYWH(half, half, half, half), paint)
        paint.color = 0xFFE8C36B.toInt()
        canvas.drawCircle(half, half * 0.85f, side * 0.22f, paint)
        paint.color = 0xFF2E86AB.toInt()
        canvas.drawRect(Rect.makeXYWH(0f, side * 0.78f, side.toFloat(), side * 0.22f), paint)
        return SkImage.makeFromBitmap(bitmap).toComposeImageBitmap()
    }
}
