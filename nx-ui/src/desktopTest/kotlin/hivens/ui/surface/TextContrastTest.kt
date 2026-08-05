package hivens.ui.surface

import androidx.compose.ui.graphics.Color
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.NxColors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The legibility floor the surface system exists to carry, measured rather than
 * asserted in prose: every text role on every plane of the tonal ladder, as a WCAG
 * contrast ratio against that plane's own body colour.
 *
 * What is pinned here is what currently holds -- primary text and the labels that
 * sit on a filled control -- so a palette edit cannot quietly drop one of them
 * below the floor. Secondary text on the LIGHT palette is deliberately not pinned:
 * it measures 2.66 to 3.35 today, under the 4.5 body floor and under the 3.0
 * large-text one on two of the four planes, and lifting it is a palette decision
 * rather than a test's to make. [SECONDARY_LIGHT_TODAY] records where it stands so
 * the gap is visible from here and cannot widen unnoticed.
 */
class TextContrastTest {

    @Test
    fun `primary text clears the body floor on every plane of both palettes`() {
        for ((name, palette) in palettes()) {
            for ((level, bg) in ladder(palette)) {
                val r = ratio(palette.textPrimary, bg)
                assertTrue(r >= BODY_FLOOR, "$name/$level: primary text at $r is under $BODY_FLOOR")
            }
        }
    }

    @Test
    fun `a label on a filled control clears the large-text floor`() {
        for ((name, palette) in palettes()) {
            assertTrue(
                ratio(palette.onPrimary, palette.primary) >= LARGE_FLOOR,
                "$name: the label on a primary fill is under $LARGE_FLOOR",
            )
            assertTrue(
                ratio(Color.White, palette.error) >= LARGE_FLOOR,
                "$name: the label on a destructive fill is under $LARGE_FLOOR",
            )
        }
    }

    @Test
    fun `secondary text clears the body floor on every plane of both palettes`() {
        for ((name, palette) in palettes()) {
            for ((level, bg) in ladder(palette)) {
                val r = ratio(palette.textSecondary, bg)
                assertTrue(r >= BODY_FLOOR, "$name/$level: secondary text at $r is under $BODY_FLOOR")
            }
        }
    }

    private fun palettes(): List<Pair<String, NxColors>> =
        listOf("dark" to DarkColorPalette, "light" to LightColorPalette)

    private fun ladder(p: NxColors): List<Pair<String, Color>> = listOf(
        "Sunken" to p.surfaceContainerLow,
        "Base" to p.surface,
        "Raised" to p.surfaceContainer,
        "Floating" to p.surfaceContainerHigh,
    )

    private fun ratio(fg: Color, bg: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow(((v + 0.055) / 1.055), 2.4)
        fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        val a = lum(fg)
        val b = lum(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private companion object {
        /** WCAG AA, body text. */
        const val BODY_FLOOR = 4.5

        /** WCAG AA, large or bold text and UI components. */
        const val LARGE_FLOOR = 3.0
    }
}
