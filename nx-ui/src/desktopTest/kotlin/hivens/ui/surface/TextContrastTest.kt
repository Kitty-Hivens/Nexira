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

    @Test
    fun `every severity accent clears the large-text floor on every plane of both palettes`() {
        // The accents were unmeasured, and one had drifted the wrong way: the light
        // palette lightened `success` while darkening the other three, leaving it at
        // 1.9 to 2.2 against the planes where the dark palette's own measures 5.2 to
        // 6.7. `warnAccent` sat at 2.9 on the plane the meta chips and callouts
        // actually sit on. Both are text-sized roles, so the floor is only a floor --
        // what this catches is a token going the wrong direction, not a palette that
        // wants retuning.
        for ((name, palette) in palettes()) {
            for ((role, accent) in severities(palette)) {
                for ((level, bg) in ladder(palette)) {
                    val r = ratio(accent, bg)
                    assertTrue(r >= LARGE_FLOOR, "$name/$level: $role at $r is under $LARGE_FLOOR")
                }
            }
        }
    }

    @Test
    fun `a light severity accent is never lighter than its dark counterpart`() {
        // The pattern the palette already follows for three of the four: a light
        // ground needs a darker ink, not a brighter one. `success` was the exception
        // and it is what made it unreadable.
        for ((role, light) in severities(LightColorPalette)) {
            val dark = severities(DarkColorPalette).first { it.first == role }.second
            assertTrue(
                luminance(light) <= luminance(dark),
                "$role is lighter on the light palette than on the dark one",
            )
        }
    }

    private fun severities(p: NxColors): List<Pair<String, Color>> = listOf(
        "success" to p.success,
        "warnAccent" to p.warnAccent,
        "criticalAccent" to p.criticalAccent,
        "progressAccent" to p.progressAccent,
    )

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow(((v + 0.055) / 1.055), 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    @Test
    fun `the surface roles a plane can take are all distinguishable from each other`() {
        // The failure this catches is not a contrast one: `glassSurfaceAlpha` returned
        // the SAME opaque colour for every requested depth on a light palette, so all
        // ninety of its call sites drew one flat white and a card, its page and a
        // nested panel were literally the same colour. A ladder whose rungs collapse
        // has the same effect, so measure that the rungs are apart -- in perceptual
        // lightness, which is what "can I see the difference" actually asks.
        for ((name, palette) in palettes()) {
            val rungs = listOf("background" to palette.background) + ladder(palette)
            for (i in rungs.indices) {
                for (j in i + 1 until rungs.size) {
                    val d = kotlin.math.abs(lstar(rungs[i].second) - lstar(rungs[j].second))
                    assertTrue(
                        d >= LADDER_STEP,
                        "$name: ${rungs[i].first} and ${rungs[j].first} are ${"%.2f".format(d)} L* apart, " +
                            "under $LADDER_STEP -- they will read as one surface",
                    )
                }
            }
        }
    }

    @Test
    fun `a divider is visible against every plane it can sit on`() {
        // The news rail drew its dividers with the surface helper, so on light they
        // were white lines on white. A divider is a line role, not a body one.
        for ((name, palette) in palettes()) {
            for ((level, bg) in ladder(palette)) {
                val d = kotlin.math.abs(lstar(palette.outline) - lstar(bg))
                assertTrue(d >= LADDER_STEP, "$name/$level: the outline is only ${"%.2f".format(d)} L* from the plane")
            }
        }
    }

    private fun lstar(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow(((v + 0.055) / 1.055), 2.4)
        val y = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        return if (y > 0.008856) 116 * Math.cbrt(y) - 16 else 903.3 * y
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

        /**
         * Perceptual-lightness gap below which two planes read as one surface.
         * Deliberately modest: this is a collapse detector, not a spacing opinion.
         */
        const val LADDER_STEP = 1.5
    }
}
