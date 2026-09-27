package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * The layouts descriptions build out of tables and sized images, drawn off-screen
 * and read back by colour.
 *
 * The pictures are solid red and carried in `data:` sources, so nothing here
 * reaches the network and the page contains exactly one colour that can only
 * have come from a picture. Where the red lands says where the renderer put it.
 */
class HtmlLayoutRenderTest {

    private val red: String by lazy {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(255, 0, 0)
        g.fillRect(0, 0, 40, 20)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Columns of the frame that hold red somewhere, as a range, or null when there is none. */
    private class RedSpan(val first: Int, val last: Int)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, html: String, width: Int = 1000, height: Int = 600): RedSpan? {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    HtmlBody(html, Modifier.fillMaxWidth(), onLink = {})
                }
            }
        }
        var span: RedSpan? = null
        try {
            // Until the pictures have decoded and the scene is still, bounded by a
            // deadline only a real hang reaches. The parse runs off the composition
            // and the decode off the frame, so a frame count would be a guess.
            val deadline = System.nanoTime() + 15_000_000_000L
            var t = 0L
            while (true) {
                val frame = scene.render(t)
                t += FRAME_NANOS
                span = redSpan(Bitmap.makeFromImage(frame))
                if ((span != null && !scene.hasInvalidations()) || System.nanoTime() > deadline) {
                    Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
                    frame.close()
                    break
                }
                frame.close()
                Thread.sleep(20)
            }
        } finally {
            scene.close()
        }
        return span
    }

    private fun redSpan(bmp: Bitmap): RedSpan? {
        var first = -1
        var last = -1
        for (x in 0 until bmp.width) {
            var y = 0
            while (y < bmp.height) {
                val c = bmp.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 200 && g < 60 && b < 60) {
                    if (first < 0) first = x
                    last = x
                    break
                }
                y += 2
            }
        }
        return if (first < 0) null else RedSpan(first, last)
    }

    /**
     * A picture in one cell and a heading over a paragraph in the other, which is
     * how Aquamirae lays out its whole page. The picture used to be reduced to its
     * alt text, so nothing red was drawn at all.
     */
    @Test
    fun `a picture in a table cell is drawn, inside its sixty percent column`() {
        val span = render(
            "html-layout-table.png",
            """
            <table><tr>
              <td width="60%"><img width="100%" src="$red"></td>
              <td width="40%"><h1>The Ice Maze</h1><p>A frozen labyrinth rises above a sunken ship graveyard.</p></td>
            </tr></table>
            """.trimIndent(),
        )
        assertTrue(span != null, "the picture in the cell was not drawn")
        assertTrue(span.first < 60, "the picture should start at the left of the table, started at ${span.first}")
        assertTrue(span.last in 520..620, "the picture should end near the sixty percent mark, ended at ${span.last}")
    }

    @Test
    fun `a percentage image takes that share of the column`() {
        val span = render("html-layout-percent.png", """<p><img width=50% src="$red"></p>""")
        assertTrue(span != null, "the picture was not drawn")
        val drawn = span.last - span.first + 1
        assertTrue(drawn in 470..530, "a fifty percent picture in a thousand pixel column drew $drawn wide")
    }

    /** The row of cards at the foot of Aquamirae's page: two to a line, each 45 percent. */
    @Test
    fun `two forty-five percent images sit side by side`() {
        val span = render(
            "html-layout-pair.png",
            """
            <center>
              <a href="https://example.invalid/a"><img style="display: inline;" src="$red" width="45%" /></a>
              <a href="https://example.invalid/b"><img style="display: inline;" src="$red" width="45%" /></a>
            </center>
            """.trimIndent(),
        )
        assertTrue(span != null, "the pair was not drawn")
        val drawn = span.last - span.first + 1
        assertTrue(drawn in 880..930, "two forty-five percent pictures and the gap between them spanned $drawn")
    }
}
