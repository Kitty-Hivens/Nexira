package hivens.ui.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import hivens.core.io.IconProcessor
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits an icon into [maxSide] px and re-encodes it as PNG, with the same rules as the
 * desktop processor: small icons pass through, an undecodable payload is kept only when
 * it is small enough to be harmless, and the header is read before the pixels so an
 * oversized raster is refused instead of materialised.
 */
class AndroidIconProcessor(
    private val maxSide: Int = 128,
    private val keepUndecodedUpTo: Int = 64 * 1024,
    private val maxDecodePixels: Long = 16_000_000L,
) : IconProcessor {

    override fun process(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > maxDecodePixels) return keepIfSmall(bytes)
        if (w <= maxSide && h <= maxSide && bytes.size <= keepUndecodedUpTo) return bytes

        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return keepIfSmall(bytes)
        val scale = min(1.0, min(maxSide.toDouble() / w, maxSide.toDouble() / h))
        val scaled = Bitmap.createScaledBitmap(image, max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), true)
        val buffer = ByteArrayOutputStream()
        return if (scaled.compress(Bitmap.CompressFormat.PNG, 100, buffer)) buffer.toByteArray() else keepIfSmall(bytes)
    }

    private fun keepIfSmall(bytes: ByteArray): ByteArray? = bytes.takeIf { it.size <= keepUndecodedUpTo }
}
