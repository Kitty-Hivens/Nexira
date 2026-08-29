package hivens.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaletteEngineTest {

    private val base = DarkColorPalette

    @Test
    fun seedDrivesAccentAndSurfaces() {
        val p = seededNxColors(base, 0xFF3B82F6.toInt(), dark = true) // blue seed
        // Accent + surfaces are seed-derived now, not the base purple / neutral grey.
        assertNotEquals(base.primary, p.primary)
        assertNotEquals(base.surface, p.surface)
        // The surface tiers are distinct planes -- the separation tone alone couldn't give.
        assertNotEquals(p.background, p.surfaceContainerHigh)
        assertNotEquals(p.surface, p.surfaceContainerHigh)
    }

    @Test
    fun deterministic() {
        val seed = 0xFFEC4899.toInt()
        assertEquals(
            seededNxColors(base, seed, dark = true),
            seededNxColors(base, seed, dark = true),
        )
    }

    @Test
    fun warmAndCoolSeedsDiffer() {
        val warm = seededNxColors(base, 0xFFE0533A.toInt(), dark = true) // red-orange
        val cool = seededNxColors(base, 0xFF3B82F6.toInt(), dark = true) // blue
        assertNotEquals(warm.primary, cool.primary)
        assertNotEquals(warm.surfaceContainerHigh, cool.surfaceContainerHigh)
    }

    // --- the wallpaper-seeding switch ---

    private val seed = 0xFF3B82F6.toInt()

    // A preset supplies a seed of its own; these pin the wallpaper half in
    // isolation, so they pass themeSeed = null. ThemeGroundTest covers the rest.

    @Test
    fun seedingOffIgnoresTheWallpaper() {
        assertEquals(DarkColorPalette, resolveBasePalette(dark = true, seed = seed, fromWallpaper = false, themeSeed = null))
        assertEquals(LightColorPalette, resolveBasePalette(dark = false, seed = seed, fromWallpaper = false, themeSeed = null))
    }

    @Test
    fun seedingOnDerivesFromTheSeed() {
        assertEquals(
            seededNxColors(DarkColorPalette, seed, dark = true),
            resolveBasePalette(dark = true, seed = seed, fromWallpaper = true, themeSeed = null),
        )
    }

    @Test
    fun withNeitherSeedThePaletteIsTheFixedOne() {
        assertEquals(DarkColorPalette, resolveBasePalette(dark = true, seed = null, fromWallpaper = true, themeSeed = null))
    }

    @Test
    fun brandTokensPreserved() {
        val p = seededNxColors(base, 0xFF22C55E.toInt(), dark = true)
        // Brand identity is not the engine's to decide -- a source badge means the
        // same thing whatever the wallpaper is. Severity is generated (see
        // PaletteSpecTest): it has to track the scheme's contrast, so it cannot
        // stay a fixed literal.
        assertEquals(base.originModrinth, p.originModrinth)
        assertEquals(base.originSmartycraft, p.originSmartycraft)
        assertEquals(base.decorativeRamp, p.decorativeRamp)
        assertNotEquals(base.warnAccent, p.warnAccent)
    }

    @Test
    fun seedingTintsWithoutRelighting() {
        // A wallpaper may say what colour a plane is. It may not say how light it
        // is: tone carries the depth the surface ladder is built on and the
        // contrast every text role was measured against. The scheme has its own
        // opinion -- on a light ground it puts `surface` and `background` at one
        // near-white tone -- and letting it through collapsed the panel into the
        // page and pulled the ladder from 12.2 L* to 6.
        for (seed in listOf(0xFFD9A0B8.toInt(), 0xFF3B82F6.toInt(), 0xFF9AA0A6.toInt(), 0xFF000000.toInt())) {
            for (dark in listOf(true, false)) {
                val fixed = if (dark) DarkColorPalette else LightColorPalette
                val seeded = seededNxColors(fixed, seed, dark)
                for ((role, pick) in ladderRoles) {
                    val before = lstar(pick(fixed))
                    val after = lstar(pick(seeded))
                    assertTrue(
                        kotlin.math.abs(before - after) <= 1.0,
                        "seed $seed ${if (dark) "dark" else "light"}: $role moved " +
                            "${"%.2f".format(before)} -> ${"%.2f".format(after)} L*",
                    )
                }
                assertNotEquals(
                    seeded.background, seeded.surface,
                    "a panel and the page must not resolve to one colour",
                )
            }
        }
    }

    private val ladderRoles = listOf<Pair<String, (NxColors) -> androidx.compose.ui.graphics.Color>>(
        "background" to { it.background },
        "surfaceContainerLow" to { it.surfaceContainerLow },
        "surface" to { it.surface },
        "surfaceContainer" to { it.surfaceContainer },
        "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "textPrimary" to { it.textPrimary },
        "textSecondary" to { it.textSecondary },
    )

    private fun lstar(c: androidx.compose.ui.graphics.Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow(((v + 0.055) / 1.055), 2.4)
        val y = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        return if (y > 0.008856) 116 * Math.cbrt(y) - 16 else 903.3 * y
    }
}
