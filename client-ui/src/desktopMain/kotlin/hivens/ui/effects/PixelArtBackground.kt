package hivens.ui.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic pixel-art fill for catalogue cards that have no banner image.
 * A tiny [seed]-derived bitmap (one art-pixel per texel) is scaled up with
 * nearest-neighbour [FilterQuality.None], so the blocks stay crisp instead of
 * blurring -- a stylised stand-in for the flat origin gradient. The same seed
 * always paints the same picture, so a pack's card looks identical across
 * scrolls and sessions. [colorA]/[colorB] are the pack's themed decorative
 * pair, keeping the art on-palette yet varied from card to card.
 *
 * Drawn through [drawWithCache] so the bitmap is built once per size, not per
 * frame; the grid is a few hundred integer ops and lives behind a dark scrim.
 */
fun Modifier.pixelArtBackground(seed: String, colorA: Color, colorB: Color): Modifier =
    drawWithCache {
        val edge = PIXEL_EDGE.toPx().coerceAtLeast(1f)
        val cols = max(1, ceil(size.width / edge).toInt())
        val rows = max(1, ceil(size.height / edge).toInt())
        val bitmap = pixelArtBitmap(seed.hashCode(), cols, rows, pixelPalette(colorA, colorB))
        onDrawBehind {
            drawImage(
                image         = bitmap,
                dstSize       = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }

/** Edge of one art-pixel; large enough to read as blocks, not noise. */
private val PIXEL_EDGE = 16.dp

/** Sparse-highlight salt, kept distinct from the field/dither hashes. */
private const val SPARKLE = 0x5bd1e995

/** Five shades spanning the two themed hues, dark enough to sit under white text. */
private fun pixelPalette(a: Color, b: Color): IntArray = intArrayOf(
    lerp(a, Color.Black, 0.74f).toArgb(),
    lerp(a, Color.Black, 0.52f).toArgb(),
    lerp(lerp(a, b, 0.5f), Color.Black, 0.34f).toArgb(),
    lerp(b, Color.Black, 0.20f).toArgb(),
    lerp(b, Color.White, 0.10f).toArgb(),
)

private fun pixelArtBitmap(seed: Int, cols: Int, rows: Int, palette: IntArray): ImageBitmap {
    val img = BufferedImage(cols, rows, BufferedImage.TYPE_INT_ARGB)
    val last = palette.size - 1
    for (y in 0 until rows) {
        // Top rows brighter -> a sense of light falling from above.
        val grad = if (rows > 1) 1f - y.toFloat() / (rows - 1) else 1f
        for (x in 0 until cols) {
            val region = unit(seed, x / 3, y / 3)          // coarse 3x3 blocks
            val dither = unit(seed * 31 + 7, x, y)         // per-pixel texture
            val t = 0.46f * region + 0.42f * grad + 0.12f * dither
            var idx = (t * last).roundToInt().coerceIn(0, last)
            // Sparse specks lift the field without it reading as static.
            if (unit(seed xor SPARKLE, x, y) > 0.94f) idx = last
            img.setRGB(x, y, palette[idx])
        }
    }
    return img.toComposeImageBitmap()
}

/** Deterministic [0,1) hash of (seed, x, y) -- a small integer mixer. */
private fun unit(seed: Int, x: Int, y: Int): Float {
    var h = seed * 73856093 xor (x * 19349663) xor (y * 83492791)
    h = h xor (h ushr 13)
    h *= -1640531527
    h = h xor (h ushr 16)
    return ((h ushr 8) and 0xFFFF) / 65535f
}
