package hivens.ui.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageIoIconProcessorTest {

    private val processor = ImageIoIconProcessor()

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(30, 144, 255)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        return image.width to image.height
    }

    @Test
    fun `oversized icon is fitted into 128px preserving aspect`() {
        val result = assertNotNull(processor.process(png(512, 256)))
        assertEquals(128 to 64, dimensionsOf(result))
    }

    @Test
    fun `small icon passes through byte-identical`() {
        val original = png(64, 64)
        val result = processor.process(original)
        assertTrue(original.contentEquals(result ?: ByteArray(0)))
    }

    @Test
    fun `undecodable payload is kept only when small`() {
        val smallJunk = ByteArray(10 * 1024) { it.toByte() }
        assertTrue(smallJunk.contentEquals(processor.process(smallJunk) ?: ByteArray(0)))

        val bigJunk = ByteArray(200 * 1024) { it.toByte() }
        assertNull(processor.process(bigJunk))
    }

    @Test
    fun `image over the pixel ceiling is refused before rasterizing`() {
        // 1000x1000 = 1 MP against a 0.5 MP ceiling stands in for a real
        // decompression bomb: the dimension probe must refuse it before the
        // full decode, and with a zero keep-threshold the icon is dropped.
        val bomb = png(1000, 1000)
        val tightened = ImageIoIconProcessor(keepUndecodedUpTo = 0, maxDecodePixels = 500_000L)
        assertNull(tightened.process(bomb))
    }

    @Test
    fun `image under the pixel ceiling still downscales normally`() {
        val result = assertNotNull(ImageIoIconProcessor(maxDecodePixels = 16_000_000L).process(png(2000, 2000)))
        assertEquals(128 to 128, dimensionsOf(result))
    }
}
