package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

// The colour arithmetic nx-ui does on a theme's behalf. HCT tone is CIE L*, so every
// lightness and contrast question here is asked of one number, and materialkolor
// answers the inverse one ("which tone reaches this ratio") directly.

internal fun Color.hct(): Hct = Hct.fromInt(copy(alpha = 1f).toArgb())

internal fun Hct.toColor(): Color = Color(toInt())

/** CIE L*, 0..100. */
internal fun Color.lstar(): Double = hct().tone

/** WCAG contrast ratio between two opaque colours. */
internal fun contrast(a: Color, b: Color): Double = Contrast.ratioOfTones(a.lstar(), b.lstar())

/**
 * [colour] moved in tone, and only in tone, until it reaches [ratio] against [ground].
 *
 * Hue and chroma stay, so the answer is still the theme's colour, only lighter or
 * darker. It moves away from the ground on the side it already sits on, and crosses
 * to the other side only when that side cannot reach the ratio at all.
 */
internal fun fit(colour: Color, ground: Color, ratio: Double): Color {
    val c = colour.hct()
    val g = ground.lstar()
    if (Contrast.ratioOfTones(c.tone, g) >= ratio) return colour
    val up = Contrast.lighter(g, ratio)
    val down = Contrast.darker(g, ratio)
    val tone = when {
        c.tone >= g && up >= 0.0 -> up
        c.tone < g && down >= 0.0 -> down
        up >= 0.0 -> up
        down >= 0.0 -> down
        else -> if (g < 50.0) 100.0 else 0.0
    }
    return Hct.from(c.hue, c.chroma, tone).toColor().copy(alpha = colour.alpha)
}

/** Shortest distance between two hues on the circle, in degrees. */
internal fun hueDistance(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/** CIE76 difference. Coarse, and enough to say whether two colours read as one. */
internal fun deltaE(a: Color, b: Color): Double {
    val p = lab(a)
    val q = lab(b)
    return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
}

private fun lab(c: Color): DoubleArray {
    fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    val r = lin(c.red)
    val g = lin(c.green)
    val b = lin(c.blue)
    val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
    return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
}

internal fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val s = sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
