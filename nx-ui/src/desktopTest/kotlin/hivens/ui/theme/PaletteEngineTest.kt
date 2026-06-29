package hivens.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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

    @Test
    fun brandAndSemanticTokensPreserved() {
        val p = seededNxColors(base, 0xFF22C55E.toInt(), dark = true)
        // Only the M3 roles change; brand / semantic tokens stay from the base.
        assertEquals(base.originModrinth, p.originModrinth)
        assertEquals(base.decorativeRamp, p.decorativeRamp)
        assertEquals(base.warnAccent, p.warnAccent)
    }
}
