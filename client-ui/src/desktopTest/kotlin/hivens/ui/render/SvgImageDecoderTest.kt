package hivens.ui.render

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture is a real shields.io badge, saved as served. It is the shape of SVG
 * a pack description actually carries: a size declared on the root with no
 * viewBox, two flat rectangles, and the text that says what the badge is for.
 */
class SvgImageDecoderTest {

    private val badge: ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/svg/shields-badge.svg")) { "fixture missing" }
            .use { it.readBytes() }

    @Test
    fun `a badge is rasterised at the size it declares`() {
        val bitmap = assertNotNull(SvgImageDecoder.renderSvg(badge))

        // The document says width="203.25" height="28"; a fractional edge rounds up.
        assertEquals(204, bitmap.width)
        assertEquals(28, bitmap.height)
    }

    @Test
    fun `the badge's text is drawn, not just its blocks`() {
        val bitmap = assertNotNull(SvgImageDecoder.renderSvg(badge))
        // Dumped next to the other render output so the glyphs can be looked at.
        Files.createDirectories(Path.of("build/render"))
        Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { Files.write(Path.of("build/render", "svg-badge.png"), it) }

        // The label sits on #ba743d and the message on #393939; the lettering on
        // both is white. Counting near-white pixels separates "the blocks were
        // filled" from "the badge was rendered" -- which is the whole difference
        // between this renderer and the one it replaces.
        var white = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val c = bitmap.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 200 && g > 200 && b > 200) white++
            }
        }
        assertTrue(white > 100, "only $white near-white pixels -- the blocks drew but the lettering did not")
    }

    @Test
    fun `the badge's logo is drawn`() {
        val bitmap = assertNotNull(SvgImageDecoder.renderSvg(badge))

        // The logo is an <image> whose source is an inline SVG, which the renderer
        // draws through a raster path and so drops entirely -- a blank square on
        // the label where the icon belongs. It sits at x=9..23, y=7..21 and is
        // white on the orange block.
        var white = 0
        for (y in 7 until 21) {
            for (x in 9 until 23) {
                val c = bitmap.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 200 && g > 200 && b > 200) white++
            }
        }
        assertTrue(white > 20, "only $white lit pixels where the logo goes -- it was dropped, not drawn")
    }

    @Test
    fun `the label block keeps its own colour`() {
        val bitmap = assertNotNull(SvgImageDecoder.renderSvg(badge))
        // A point inside the left rect, clear of the logo and the lettering.
        val c = bitmap.getColor(4, 4)
        assertEquals(0xba, (c shr 16) and 0xFF)
        assertEquals(0x74, (c shr 8) and 0xFF)
        assertEquals(0x3d, c and 0xFF)
    }

    @Test
    fun `something that is not a document is refused rather than guessed at`() {
        assertNull(SvgImageDecoder.renderSvg("not markup at all".toByteArray()))
    }

    @Test
    fun `a root tag is found behind a prolog`() {
        assertTrue(SvgImageDecoder.looksLikeSvg("""<?xml version="1.0"?><!-- a note --><svg xmlns="">""".toByteArray()))
        assertTrue(SvgImageDecoder.looksLikeSvg(badge))
        assertFalse(SvgImageDecoder.looksLikeSvg("PNG image data".toByteArray()))
    }
}
