package hivens.themes.api

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

// The colour arithmetic done beside a theme: comparing its colours and making
// new ones next to them. HCT tone is CIE L*, so every lightness and contrast question
// here is asked of one number. Nothing here changes a colour the theme gave.

internal fun Color.hct(): Hct = Hct.fromInt(copy(alpha = 1f).toArgb())

internal fun Hct.toColor(): Color = Color(toInt())

/** CIE L*, 0..100. */
internal fun Color.lstar(): Double = hct().tone

/** WCAG contrast ratio between two opaque colours. */
internal fun contrast(a: Color, b: Color): Double = Contrast.ratioOfTones(a.lstar(), b.lstar())

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
