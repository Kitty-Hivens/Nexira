package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaStyle
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

    private val media = List(5) {
        GalleryMedia.Image(thumb = "https://example.invalid/$it.png", full = "https://example.invalid/$it.png")
    }

    private val pad = 24

    private fun render(width: Int, name: String): Bitmap {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, 700, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = CelestiaStyle) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(pad.dp)) {
                    ImageGallery(media = media)
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
        val columns = (avail + gap) / (300 + gap)
        assertEquals(3, columns, "the arithmetic under test")
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
    fun `a narrow pane drops to one column rather than to none`() {
        val width = 560
        val bmp = render(width, "gallery-narrow.png")
        val avail = width - 2 * pad
        assertEquals(1, (avail + 12) / (300 + 12), "the arithmetic under test")

        assertTrue(bmp.isCell(pad + avail / 2, pad + avail / 8), "the single column did not render")
    }
}
