package hivens.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

// Pixels fed to the quantizer / colours it reduces to. A wallpaper is subsampled
// to the budget first, so a 4K image (or video frame) doesn't stall extraction.
private const val SEED_SAMPLE_BUDGET = 12_000
private const val SEED_QUANTIZE_COLORS = 96

/**
 * Material-You seed colour (ARGB) from a static wallpaper bitmap: quantize the
 * pixels (Celebi) -> score -> top-ranked colour. Returns null when the bitmap is
 * empty or scoring finds nothing usable (then the theme keeps its fixed palette).
 * Pure -- no Compose state, no IO.
 */
fun seedFromImage(bitmap: ImageBitmap): Int? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels)
    return seedFromArgb(pixels, pixels.size)
}

/**
 * Seed colour from a raw RGBA frame (the video wallpaper's decoded buffer): subsample
 * + convert to ARGB inline (no full-frame allocation), then quantize -> score. Same
 * null contract as [seedFromImage].
 */
fun seedFromRgba(rgba: ByteArray, width: Int, height: Int): Int? {
    val n = width * height
    if (n <= 0 || rgba.size < n * 4) return null
    val step = maxOf(1, n / SEED_SAMPLE_BUDGET)
    val count = (n + step - 1) / step
    val argb = IntArray(count) { j ->
        val o = j * step * 4
        val r = rgba[o].toInt() and 0xFF
        val g = rgba[o + 1].toInt() and 0xFF
        val b = rgba[o + 2].toInt() and 0xFF
        val a = rgba[o + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    val quantized = QuantizerCelebi.quantize(argb, SEED_QUANTIZE_COLORS)
    return Score.score(quantized).firstOrNull()
}

private fun seedFromArgb(pixels: IntArray, length: Int): Int? {
    if (length <= 0) return null
    val step = maxOf(1, length / SEED_SAMPLE_BUDGET)
    val sampled = if (step == 1) pixels else IntArray((length + step - 1) / step) { pixels[it * step] }
    val quantized = QuantizerCelebi.quantize(sampled, SEED_QUANTIZE_COLORS)
    return Score.score(quantized).firstOrNull()
}

/**
 * Average perceived brightness (0..1) of a wallpaper bitmap -- the Rec.709 luma over a
 * subsampled scan. Used to match the dark/light theme to the wallpaper: below ~0.5 reads
 * as a dark image. Distinct from [seedFromImage], which returns the most VIVID colour,
 * not the overall lightness (a dark image with a bright accent has a bright seed).
 */
fun averageLuminanceFromImage(bitmap: ImageBitmap): Float? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels)
    val step = maxOf(1, pixels.size / SEED_SAMPLE_BUDGET)
    var sum = 0.0
    var n = 0
    var i = 0
    while (i < pixels.size) { sum += luminanceOfArgb(pixels[i]); n++; i += step }
    return if (n > 0) (sum / n).toFloat() else null
}

/** Rec.709 luma (0..1) of one 0xAARRGGBB colour. */
fun luminanceOfArgb(argb: Int): Float {
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
