package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Choosing a theme has to change the ground.
 *
 * It did not. Seeding ran only from a wallpaper, so on a fresh install -- which has
 * none -- nothing seeded anything and every preset drew the same near-black page.
 * The preset reached five accent fields and stopped there, which is why the launcher
 * looked the same whichever theme was picked.
 *
 * The preset's lead colour is the seed now when the wallpaper has none to give. What
 * that must NOT do is move the tonal ladder: every contrast ratio in the tree was
 * measured against tone, so the seed is spent on hue and chroma and lightness stays
 * exactly where the fixed palette put it.
 */
class ThemeGroundTest {

    private fun seedOf(theme: CustomTheme) = CustomTheme.parseHexColor(theme.primary).toArgb()

    private fun ground(theme: CustomTheme?, dark: Boolean = true): NxColors =
        resolveBasePalette(dark, seed = null, fromWallpaper = true, themeSeed = theme?.let(::seedOf))

    @Test
    fun `with no theme and no wallpaper the palette is the fixed one`() {
        assertEquals(DarkColorPalette, ground(null))
        assertEquals(LightColorPalette, ground(null, dark = false))
    }

    @Test
    fun `a theme colours the ground even with no wallpaper`() {
        val fixed = DarkColorPalette.background
        val matrix = ground(ThemePresets.MATRIX).background
        assertTrue(matrix != fixed, "Matrix drew the stock ground: $matrix")
    }

    /**
     * Presets of different hue must not share a ground. Presets of the SAME hue may:
     * the tint is deliberately weak and a ground is near-black, so two pinks a few
     * degrees apart quantise to one 8-bit colour. Vaporwave and Lotus Dark do exactly
     * that. Asserting all nine are distinct would be asserting the tint is stronger
     * than it is meant to be.
     */
    @Test
    fun `presets of different hue give different grounds`() {
        for (theme in ThemePresets.getAll()) {
            val c = ground(theme).background
            println(
                "ThemeGroundTest: %-14s ground #%02X%02X%02X  L* %.2f (stock #121212, L* %.2f)".format(
                    theme.name,
                    (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(),
                    lstar(c), lstar(DarkColorPalette.background),
                ),
            )
        }
        val byHue = listOf(ThemePresets.MATRIX, ThemePresets.BLOOD_RAIN, ThemePresets.ABYSSAL, ThemePresets.CELESTIA_DARK)
            .associate { it.name to ground(it).background }
        assertEquals(byHue.size, byHue.values.toSet().size, "presets of different hue share a ground: $byHue")
    }

    @Test
    fun `the ground changes colour without changing lightness`() {
        for (theme in ThemePresets.getAll()) {
            for (dark in listOf(true, false)) {
                val fixed = if (dark) DarkColorPalette else LightColorPalette
                val seeded = ground(theme, dark)
                for ((name, pair) in planes(fixed, seeded)) {
                    val delta = abs(lstar(pair.first) - lstar(pair.second))
                    assertTrue(
                        delta <= 1.0,
                        "${theme.name} moved $name by ${"%.2f".format(delta)} L* on ${if (dark) "dark" else "light"}",
                    )
                }
            }
        }
    }

    @Test
    fun `a wallpaper seed wins over the theme`() {
        val theme = ThemePresets.MATRIX
        val wallpaper = CustomTheme.parseHexColor("#FF6600").toArgb()
        val fromWallpaper = resolveBasePalette(true, wallpaper, fromWallpaper = true, themeSeed = seedOf(theme))
        val fromTheme = ground(theme)
        assertTrue(
            fromWallpaper.background != fromTheme.background,
            "the wallpaper's seed did not take precedence over the preset's",
        )
    }

    @Test
    fun `seeding switched off still lets the theme through`() {
        // The switch says "take the colours from the wallpaper". Off means the
        // wallpaper is not consulted, not that the preset stops applying.
        val off = resolveBasePalette(true, seed = 0xFFFF6600.toInt(), fromWallpaper = false, themeSeed = seedOf(ThemePresets.MATRIX))
        assertEquals(ground(ThemePresets.MATRIX).background, off.background)
    }

    private fun planes(a: NxColors, b: NxColors): List<Pair<String, Pair<Color, Color>>> = listOf(
        "background" to (a.background to b.background),
        "surface" to (a.surface to b.surface),
        "surfaceContainerLow" to (a.surfaceContainerLow to b.surfaceContainerLow),
        "surfaceContainer" to (a.surfaceContainer to b.surfaceContainer),
        "surfaceContainerHigh" to (a.surfaceContainerHigh to b.surfaceContainerHigh),
        "textPrimary" to (a.textPrimary to b.textPrimary),
        "textSecondary" to (a.textSecondary to b.textSecondary),
    )

    private fun lstar(c: Color): Double {
        val y = c.luminance().toDouble()
        return if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
    }
}
