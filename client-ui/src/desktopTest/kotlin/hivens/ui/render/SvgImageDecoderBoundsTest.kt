package hivens.ui.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bounds, tested. Every one of these was in the code and either unreachable
 * or bypassable while the file had only its happy-path tests -- the bound with no
 * test was the bound that turned out not to hold.
 *
 * The input here is what a pack description can carry: a third party's SVG,
 * fetched over the network and handed straight to a rasteriser.
 */
class SvgImageDecoderBoundsTest {

    private val hugeInner = "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI0MDAwIiBoZWlnaHQ9IjQwMDAiPjxyZWN0IHdpZHRoPSI0MDAwIiBoZWlnaHQ9IjQwMDAiIGZpbGw9InJlZCIvPjwvc3ZnPg=="

    private fun svgWithIcons(count: Int, box: String): ByteArray {
        val image = "<image " + box + "href=\"data:image/svg+xml;base64," + hugeInner + "\"/>"
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"40\">" +
            image.repeat(count) + "</svg>").toByteArray()
    }

    @Test
    fun `an icon with no box of its own cannot claim the size it likes`() {
        // Eight icons, each an inner document declaring four thousand pixels a
        // side, and no box on the outer image to hold them to it. Unbounded, each
        // is a 67MB raster and the eighth is an OutOfMemoryError -- out of two
        // kilobytes of markup.
        val bitmap = SvgImageDecoder.renderSvg(svgWithIcons(8, box = ""))

        assertNotNull(bitmap, "the document itself is ordinary and must still render")
        assertEquals(200, bitmap.width)
        assertEquals(40, bitmap.height)
    }

    @Test
    fun `a fan-out of icons stops at the budget rather than multiplying`() {
        // Inside the per-document count and the nesting depth, and still far more
        // work than a badge needs. What bounds it is the shared pixel budget.
        val bitmap = SvgImageDecoder.renderSvg(svgWithIcons(30, box = "width=\"512\" height=\"512\" "))

        assertNotNull(bitmap, "the outer document is fine; only the excess is refused")
    }

    @Test
    fun `a document larger than the ceiling is refused outright`() {
        val huge = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100000\" height=\"100000\"></svg>".toByteArray()

        assertNull(SvgImageDecoder.renderSvg(huge))
    }

    @Test
    fun `an edge exactly on the ceiling is allowed`() {
        val edge = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4096\" height=\"1\"></svg>".toByteArray()

        assertEquals(4096, assertNotNull(SvgImageDecoder.renderSvg(edge)).width)
    }

    @Test
    fun `a length is read with its unit, and a relative one is not a length`() {
        assertEquals(14, SvgImageDecoder.lengthPx("14"))
        assertEquals(14, SvgImageDecoder.lengthPx("14px"))
        assertEquals(14, SvgImageDecoder.lengthPx("14PX"))
        assertEquals(2, SvgImageDecoder.lengthPx("1pt"))
        assertEquals(16, SvgImageDecoder.lengthPx("1pc"))
        assertEquals(96, SvgImageDecoder.lengthPx("1in"))
        assertEquals(0, SvgImageDecoder.lengthPx("50%"), "a fraction of something we do not have")
        assertEquals(0, SvgImageDecoder.lengthPx("-5"))
        assertEquals(0, SvgImageDecoder.lengthPx(""))
        assertEquals(0, SvgImageDecoder.lengthPx("px"))
        assertEquals(0, SvgImageDecoder.lengthPx("NaN"))
        assertEquals(0, SvgImageDecoder.lengthPx("Infinity"))
    }

    @Test
    fun `a data uri is percent-decoded, not form-decoded`() {
        // The form decoder reads a plus as a space and refuses a lone percent,
        // and an unencoded SVG saying width='100%' contains one.
        assertEquals("width='100%'", SvgImageDecoder.percentDecode("width='100%'"))
        assertEquals("a+b", SvgImageDecoder.percentDecode("a+b"), "a plus is a plus in a data uri")
        assertEquals("<svg>", SvgImageDecoder.percentDecode("%3Csvg%3E"))
        assertEquals("100% sure", SvgImageDecoder.percentDecode("100%%20sure"))
    }

    @Test
    fun `an unencoded inline icon is drawn rather than dropped`() {
        val inner = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'>" +
            "<rect width='100%' height='100%' fill='white'/></svg>"
        val outer = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\">" +
            "<image x=\"0\" y=\"0\" width=\"20\" height=\"20\" href=\"data:image/svg+xml," + inner + "\"/></svg>").toByteArray()

        val bitmap = assertNotNull(SvgImageDecoder.renderSvg(outer))
        val c = bitmap.getColor(10, 10)
        assertTrue(
            ((c shr 16) and 0xFF) > 200 && ((c shr 8) and 0xFF) > 200 && (c and 0xFF) > 200,
            "the inline icon was dropped instead of drawn",
        )
    }

    @Test
    fun `a source past the byte ceiling is not parsed`() {
        val padding = " ".repeat(9 * 1024 * 1024)
        val fat = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">" +
            "<!--" + padding + "-->" + "</svg>").toByteArray()

        assertNull(SvgImageDecoder.renderSvg(fat), "nine megabytes of description is not an illustration")
    }
}
