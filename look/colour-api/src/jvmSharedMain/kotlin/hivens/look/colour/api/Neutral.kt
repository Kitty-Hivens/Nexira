package hivens.look.colour.api

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * The colours of the base answers: greys for a dark ground and nothing else, so a window drawn with no
 * colour provider reads as drawn in no look at all rather than in one somebody picked. The window the
 * launcher boots in and a first start are dark, which is why the ground is.
 */
internal object Neutral {
    val PAGE = Color(0xFF161616)
    val INK_MAIN = Color(0xFFE8E8E8)
    val INK_QUIET = Color(0xFFA6A6A6)
    val LEAD = Color(0xFFBDBDBD)
    val PARTNER = Color(0xFF8C8C8C)
    val STATUS = Color(0xFFB4B4B4)
    val MATCH = Color(0xFFD4D4D4)
    val MATCH_INK = Color(0xFF1A1A1A)
    const val FLOATING = 3

    /** The ladder from the page outward. */
    private val STEPS = listOf(Color(0xFF161616), Color(0xFF1D1D1D), Color(0xFF242424), Color(0xFF2C2C2C))

    /** Eight greys of different tones, light and dark in turn, so neighbours stand apart. */
    val SPREAD = listOf(
        Color(0xFFE0E0E0), Color(0xFF7A7A7A), Color(0xFFC2C2C2), Color(0xFF6B6B6B),
        Color(0xFFD0D0D0), Color(0xFF8C8C8C), Color(0xFFB0B0B0), Color(0xFF9E9E9E),
    )

    /** Step [n] of the ladder, past either end carried on by the ladder's mean step and held between black and white. */
    fun depth(n: Int): Color {
        if (n in STEPS.indices) return STEPS[n]
        val first = STEPS.first().red
        val mean = (STEPS.last().red - first) / STEPS.lastIndex
        val level = (first + mean * n).coerceIn(0f, 1f)
        val byte = (level * 255).roundToInt()
        return Color(byte, byte, byte)
    }
}
