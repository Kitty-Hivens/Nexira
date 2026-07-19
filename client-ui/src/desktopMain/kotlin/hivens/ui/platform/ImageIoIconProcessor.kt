package hivens.ui.platform

import hivens.core.io.IconProcessor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ImageIO-backed [IconProcessor]: fits an icon into [maxSide] px and re-encodes
 * as PNG. Everything in the app renders these at list-row size, so a mod's
 * multi-megabyte logo gains nothing past thumbnail dimensions -- but unbounded
 * it bloats the scan cache past the Xodus loggable limit and the content list's
 * resident memory.
 *
 * Already-small icons (dimensions AND bytes) pass through untouched, which also
 * preserves animation in tiny GIFs. An undecodable payload is kept only when it
 * is small enough to be harmless, since Coil may still know how to render it.
 * Dimensions are probed before the full decode so a decompression-bomb PNG is
 * rejected instead of materializing a gigabyte raster from a local jar.
 */
class ImageIoIconProcessor(
    private val maxSide: Int = 128,
    private val keepUndecodedUpTo: Int = 64 * 1024,
    private val maxDecodePixels: Long = 16_000_000L,
) : IconProcessor {

    override fun process(bytes: ByteArray): ByteArray? {
        val image = runCatching { readBounded(bytes) }.getOrNull()
            ?: return bytes.takeIf { it.size <= keepUndecodedUpTo }
        val w = image.width
        val h = image.height
        if (w <= maxSide && h <= maxSide && bytes.size <= keepUndecodedUpTo) return bytes

        val scale = min(1.0, min(maxSide.toDouble() / w, maxSide.toDouble() / h))
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, tw, th, null)
        } finally {
            g.dispose()
        }
        val buffer = ByteArrayOutputStream()
        return if (ImageIO.write(out, "png", buffer)) buffer.toByteArray()
        else bytes.takeIf { it.size <= keepUndecodedUpTo }
    }

    /** Decode with a pixel-count ceiling: header dimensions are read first, oversized rasters are refused. */
    private fun readBounded(bytes: ByteArray): BufferedImage? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0 || w.toLong() * h > maxDecodePixels) return null
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }
}
