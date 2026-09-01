package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Off-screen render of the screenshot grid. The images themselves never load --
 * there is no network here -- which is the point: what is under test is the
 * geometry, and an empty cell shows that better than a photograph would.
 *
 * The assertions read pixels at computed positions rather than checking that a
 * file was written. A test that only weighs the PNG passes when the grid renders
 * one column instead of three, or nothing at all.
 */
class ImageGalleryRenderTest {

    /** Uncaptioned, so a cell is exactly its frame and the geometry below is the frame's. */
    private val media = List(5) {
        GalleryMedia.Image(thumb = "https://example.invalid/$it.png", full = "https://example.invalid/$it.png")
    }

    /**
     * Five shots captioned unevenly, which is how a real gallery arrives: one
     * with nothing, one with a name only, one with a sentence long enough to
     * wrap. A fixture of five identical cells cannot tell a row that matches its
     * tallest member from one that does not.
     */
    private val captioned = listOf(
        image(0),
        image(1, title = "The starter"),
        image(
            2,
            title = "Where the run begins",
            description = "A long enough sentence about this screenshot that it has to take a second line to finish itself",
        ),
        image(3, title = "Later on"),
        image(4, description = "No name, only a note"),
    )

    private fun image(i: Int, title: String? = null, description: String? = null) = GalleryMedia.Image(
        thumb = "https://example.invalid/$i.png",
        full = "https://example.invalid/$i.png",
        title = title,
        description = description,
    )

    private val pad = 24

    private fun render(width: Int, name: String, items: List<GalleryMedia> = media): Bitmap {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, 700, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(pad.dp)) {
                    ImageGallery(media = items)
                }
            }
        }
        val frame = try {
            var t = 0L
            repeat(6) { scene.render(t); t += 16_000_000L }
            scene.render(t)
        } finally {
            scene.close()
        }
        Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
        return Bitmap.makeFromImage(frame)
    }

    /** The page ground, sampled where nothing is ever laid out. */
    private fun Bitmap.ground(): Int = getColor(2, 2)

    private fun Bitmap.isCell(x: Int, y: Int): Boolean = getColor(x, y) != ground()

    @Test
    fun `a wide pane is three columns and the cells are separated`() {
        val width = 1100
        val bmp = render(width, "gallery-wide.png")
        val avail = width - 2 * pad
        val gap = 12
        val columns = 3
        val cell = (avail - gap * (columns - 1)) / columns

        // Inside each of the three cells of the first row.
        repeat(columns) { i ->
            val cx = pad + i * (cell + gap) + cell / 2
            assertTrue(bmp.isCell(cx, pad + cell / 4), "column ${'$'}i of the first row did not render")
        }
        // And in the gaps between them, where the page must show through.
        repeat(columns - 1) { i ->
            val gx = pad + (i + 1) * cell + i * gap + gap / 2
            assertTrue(!bmp.isCell(gx, pad + cell / 4), "the gap after column ${'$'}i was filled -- the cells are touching")
        }
    }

    @Test
    fun `a short last row keeps the cell width of a full one`() {
        val width = 1100
        val bmp = render(width, "gallery-wide.png")
        val avail = width - 2 * pad
        val gap = 12
        val columns = 3
        val cell = (avail - gap * (columns - 1)) / columns
        val rowHeight = cell / 2   // cells are framed 2:1
        val secondRowY = pad + rowHeight + gap + rowHeight / 4

        assertTrue(bmp.isCell(pad + cell / 2, secondRowY), "the fourth shot is missing from the second row")
        assertTrue(bmp.isCell(pad + cell + gap + cell / 2, secondRowY), "the fifth shot is missing")
        // Five shots in three columns leaves the third place of the second row
        // empty; a stretched last cell would fill it.
        assertTrue(
            !bmp.isCell(pad + 2 * (cell + gap) + cell / 2, secondRowY),
            "the last row stretched to fill instead of keeping the cell width",
        )
    }

    @Test
    fun `a captioned shot is taller than its frame, an uncaptioned one is not`() {
        val width = 1100
        val bare = render(width, "gallery-wide.png")
        val withText = render(width, "gallery-captioned.png", captioned)
        val avail = width - 2 * 24
        val gap = 12
        val cell = (avail - gap * 2) / 3
        // Just below where an uncaptioned frame ends. Bare, the page shows through;
        // captioned, the cell continues into its caption band.
        val y = 24 + cell / 2 + 6

        assertTrue(!bare.isCell(24 + cell / 2, y), "an uncaptioned cell must end with its frame")
        assertTrue(withText.isCell(24 + cell / 2, y), "the caption band did not render under the shot")
    }

    @Test
    fun `cells in a row end together however unevenly they are captioned`() {
        val width = 1100
        val bmp = render(width, "gallery-captioned.png", captioned)
        val avail = width - 2 * pad
        val gap = 12
        val cell = (avail - gap * 2) / 3
        // The tallest caption in the first row is the wrapping one, in column
        // three. Just above where it ends, all three columns must still be cell.
        // Without a row that matches its tallest member, the first two stop with
        // their own captions and the page shows through beside the third.
        val deepest = (0 until 3).maxOf { i -> firstRowBottom(bmp, pad + i * (cell + gap) + cell / 2) }
        repeat(3) { i ->
            val cx = pad + i * (cell + gap) + cell / 2
            assertTrue(
                bmp.isCell(cx, deepest - 2),
                "column $i ended before the row did -- the cells do not share the tallest height",
            )
        }
    }

    /**
     * Where the FIRST row's cell ends at [x] -- the last cell pixel before a run
     * of page ground at least as deep as the gap between rows. Scanning to the
     * bottom of the image instead would find the second row's cell and report a
     * column that has one as deeper than a column that does not.
     */
    private fun firstRowBottom(bmp: Bitmap, x: Int): Int {
        val gap = 12
        var last = pad
        var y = pad
        while (y < bmp.height) {
            if (bmp.isCell(x, y)) {
                last = y
            } else if ((y until minOf(y + gap, bmp.height)).none { bmp.isCell(x, it) }) {
                return last
            }
            y++
        }
        return last
    }

    @Test
    fun `a narrow pane drops to one column rather than to none`() {
        val width = 560
        val bmp = render(width, "gallery-narrow.png")
        val avail = width - 2 * pad

        assertTrue(bmp.isCell(pad + avail / 2, pad + avail / 8), "the single column did not render")
        // Two columns would put a gap through the middle of the pane.
        assertTrue(bmp.isCell(pad + avail / 2, pad + avail / 4), "the pane was split into columns it has no room for")
    }
}
