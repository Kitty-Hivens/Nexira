package hivens.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

// Pixels fed to the quantizer / colours it reduces to. A wallpaper is subsampled
// to the budget first, so a 4K image (or video frame) doesn't stall extraction.
private const val SEED_SAMPLE_BUDGET = 12_000
private const val SEED_QUANTIZE_COLORS = 96

/** How many ranked colours a picture hands over: enough for a theme's own colours. */
private const val RANKED_COLOURS = 5

/**
 * The most characteristic colour (ARGB) of a static bitmap: quantize the pixels
 * (Celebi), score them, take the top one. Null when the bitmap is empty. Pure: no
 * Compose state, no IO.
 */
fun seedFromImage(bitmap: ImageBitmap): Int? = coloursFromImage(bitmap).firstOrNull()

/** The picture's characteristic colours, best first. Same contract as [seedFromImage]. */
fun coloursFromImage(bitmap: ImageBitmap): List<Int> {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return emptyList()
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels)
    return rankedFromArgb(pixels, pixels.size)
}

/**
 * The most characteristic colour of a raw RGBA frame (the video wallpaper's decoded
 * buffer). Same null contract as [seedFromImage].
 */
fun seedFromRgba(rgba: ByteArray, width: Int, height: Int): Int? = coloursFromRgba(rgba, width, height).firstOrNull()

/**
 * The frame's characteristic colours, best first: subsample and convert to ARGB
 * inline (no full-frame allocation), then quantize and score.
 */
fun coloursFromRgba(rgba: ByteArray, width: Int, height: Int): List<Int> {
    val n = width * height
    if (n <= 0 || rgba.size < n * 4) return emptyList()
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
    return ranked(argb)
}

private fun rankedFromArgb(pixels: IntArray, length: Int): List<Int> {
    if (length <= 0) return emptyList()
    val step = maxOf(1, length / SEED_SAMPLE_BUDGET)
    val sampled = if (step == 1) pixels else IntArray((length + step - 1) / step) { pixels[it * step] }
    return ranked(sampled)
}

/**
 * Scored colours, best first. A picture with nothing colourful in it still has a
 * colour: scoring filters out greys, so a grey picture falls back to the colour it is
 * made of most, rather than to nothing or to a stock blue.
 */
private fun ranked(argb: IntArray): List<Int> {
    val quantized = QuantizerCelebi.quantize(argb, SEED_QUANTIZE_COLORS)
    val scored = Score.score(quantized, RANKED_COLOURS, null, true)
    if (scored.isNotEmpty()) return scored
    return listOfNotNull(quantized.maxByOrNull { it.value }?.key)
}

/**
 * What a wallpaper tells the launcher: its characteristic [colours], best first, and
 * its overall [avgLuminance] (0..1 average brightness, below about 0.5 reads as a dark
 * image). The two are distinct: a dark image with a bright accent has a bright first
 * colour but a low average.
 */
data class WallpaperTone(val colours: List<Int>, val avgLuminance: Float?) {
    val seedArgb: Int? get() = colours.firstOrNull()

    companion object {
        val NONE = WallpaperTone(emptyList(), null)
    }
}

/**
 * Colours and average brightness in ONE [readPixels]. A large wallpaper is tens of MB
 * of pixels, and reading it twice (once per value) allocates two full arrays at once
 * and OOMs, so both are derived from a single read.
 */
fun wallpaperToneFromImage(bitmap: ImageBitmap): WallpaperTone {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return WallpaperTone.NONE
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels)
    val colours = rankedFromArgb(pixels, pixels.size)
    val step = maxOf(1, pixels.size / SEED_SAMPLE_BUDGET)
    var sum = 0.0
    var n = 0
    var i = 0
    while (i < pixels.size) { sum += luminanceOfArgb(pixels[i]); n++; i += step }
    return WallpaperTone(colours, if (n > 0) (sum / n).toFloat() else null)
}

/** Rec.709 luma (0..1) of one 0xAARRGGBB colour. */
fun luminanceOfArgb(argb: Int): Float {
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
