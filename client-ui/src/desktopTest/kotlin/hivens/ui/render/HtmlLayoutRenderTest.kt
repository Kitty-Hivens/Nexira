package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.FRAME_NANOS
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import hivens.ui.theme.NxColor

/**
 * The layouts descriptions build out of tables and sized images, drawn off-screen
 * and read back by colour.
 *
 * The pictures are solid red and carried in `data:` sources, so nothing here
 * reaches the network and the page contains exactly one colour that can only
 * have come from a picture. Where the red lands says where the renderer put it.
 *
 * The body sits in a vertical scroll, as it does on every screen that shows one.
 * That is not decoration: with the height unbounded the image loader keeps a
 * picture at its natural size, and a scene with a bounded height hid exactly the
 * defect these tests exist for.
 */
class HtmlLayoutRenderTest {

    private fun redPng(w: Int, h: Int): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(255, 0, 0)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private val red: String by lazy { redPng(40, 20) }

    /** Where red was drawn: its columns and rows, or null when there was none. */
    private data class RedBox(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private class Frame(val red: RedBox?, val bmp: Bitmap)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, html: String, width: Int = 1000, height: Int = 700): Frame {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(NxColor.page).verticalScroll(rememberScrollState())) {
                    HtmlBody(html, Modifier.fillMaxWidth(), onLink = {})
                }
            }
        }
        try {
            // Until the red has stopped moving for several frames in a row, bounded
            // by a deadline only a real hang reaches. Two pictures are two separate
            // loads, and stopping at the first quiet frame could catch one of them
            // before the other had arrived.
            val deadline = System.nanoTime() + 15_000_000_000L
            var t = 0L
            var last: RedBox? = null
            var steady = 0
            while (true) {
                val frame = scene.render(t)
                t += FRAME_NANOS
                val bmp = Bitmap.makeFromImage(frame)
                val box = redBox(bmp)
                steady = if (box != null && box == last && !scene.hasInvalidations()) steady + 1 else 0
                last = box
                if (steady >= STEADY_FRAMES || System.nanoTime() > deadline) {
                    Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
                    frame.close()
                    return Frame(box, bmp)
                }
                frame.close()
                Thread.sleep(20)
            }
        } finally {
            scene.close()
        }
    }

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r > 200 && g < 60 && b < 60
    }

    private fun redBox(bmp: Bitmap): RedBox? {
        var left = Int.MAX_VALUE
        var right = -1
        var top = Int.MAX_VALUE
        var bottom = -1
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (isRed(bmp.getColor(x, y))) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < 0) null else RedBox(left, right, top, bottom)
    }

    /** Rows holding light text pixels to the right of [fromX]. */
    private fun textRows(bmp: Bitmap, fromX: Int): IntRange? {
        var top = -1
        var bottom = -1
        for (y in 0 until bmp.height) for (x in fromX until bmp.width) {
            val c = bmp.getColor(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 170 && g > 170 && b > 170) {
                if (top < 0) top = y
                bottom = y
                break
            }
        }
        return if (top < 0) null else top..bottom
    }

    /**
     * A picture in one cell and a heading over a paragraph in the other, which is
     * how Aquamirae lays out its whole page. The picture used to be reduced to its
     * alt text, so nothing red was drawn at all.
     */
    @Test
    fun `a picture in a table cell is drawn, inside its sixty percent column`() {
        val red = render(
            "html-layout-table.png",
            """
            <table><tr>
              <td width="60%"><img width="100%" src="$red"></td>
              <td width="40%"><h1>The Ice Maze</h1><p>A frozen labyrinth rises above a sunken ship graveyard.</p></td>
            </tr></table>
            """.trimIndent(),
        ).red
        assertTrue(red != null, "the picture in the cell was not drawn")
        assertTrue(red.left < 60, "the picture should start at the left of the table, started at ${red.left}")
        assertTrue(red.right in 520..620, "the picture should end near the sixty percent mark, ended at ${red.right}")
    }

    /** Forty pixels of source at half of a thousand-pixel column is scaled up, as a browser does. */
    @Test
    fun `a small percentage image is scaled up to its share, inside a scrolling page`() {
        val red = render("html-layout-percent.png", """<p><img width=50% src="$red"></p>""").red
        assertTrue(red != null, "the picture was not drawn")
        assertTrue(red.width in 470..530, "a fifty percent picture in a thousand pixel column drew ${red.width} wide")
        assertTrue(red.height in 220..270, "it should keep its two-to-one shape, drew ${red.height} tall")
    }

    /** The row of cards at the foot of Aquamirae's page: two to a line, each 45 percent. */
    @Test
    fun `two forty-five percent images sit side by side`() {
        val red = render(
            "html-layout-pair.png",
            """
            <center>
              <a href="https://example.invalid/a"><img style="display: inline;" src="$red" width="45%" /></a>
              <a href="https://example.invalid/b"><img style="display: inline;" src="$red" width="45%" /></a>
            </center>
            """.trimIndent(),
        ).red
        assertTrue(red != null, "the pair was not drawn")
        assertTrue(red.width in 880..930, "two forty-five percent pictures and the gap between them spanned ${red.width}")
        assertTrue(red.height < 260, "the two should share a line, the red was ${red.height} tall")
    }

    /** Widths declared once, on the header, and a picture in the second column of the row below. */
    @Test
    fun `a body row lines up under the widths its header declared`() {
        val red = render(
            "html-layout-header.png",
            """
            <table>
              <tr><th width="25%">Key</th><th width="75%">Action</th></tr>
              <tr><td>R</td><td><img width="100%" src="$red"></td></tr>
            </table>
            """.trimIndent(),
        ).red
        assertTrue(red != null, "the picture was not drawn")
        assertTrue(red.left in 240..290, "the second column should start near a quarter, started at ${red.left}")
    }

    /** A mod's icon beside its name, which used to take a line of its own. */
    @Test
    fun `a small icon sits on the line beside its text`() {
        val frame = render("html-layout-icon.png", """<p><img width="16" height="16" src="${redPng(16, 16)}"> Sodium, the renderer</p>""")
        val icon = frame.red
        assertTrue(icon != null, "the icon was not drawn")
        assertTrue(icon.height <= 20, "the icon should keep its declared size, was ${icon.height} tall")
        val text = textRows(frame.bmp, icon.right + 1)
        assertTrue(text != null, "no text was drawn beside the icon")
        assertTrue(text.first <= icon.bottom && text.last >= icon.top, "the text ${text} should share rows with the icon ${icon.top}..${icon.bottom}")
    }

    private companion object {
        const val STEADY_FRAMES = 5
    }
}
