package hivens.ui.surface

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.NxColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NxSurfaceTest {

    // --- level -> ladder colour ---

    @Test
    fun `each level takes its rung of the ladder`() {
        val p = DarkColorPalette
        assertEquals(p.surfaceContainerLow, NxSurfaceLevel.Sunken.color(p))
        assertEquals(p.surface, NxSurfaceLevel.Base.color(p))
        assertEquals(p.surfaceContainer, NxSurfaceLevel.Raised.color(p))
        assertEquals(p.surfaceContainerHigh, NxSurfaceLevel.Floating.color(p))
    }

    @Test
    fun `four levels are four colours`() {
        for (palette in listOf(DarkColorPalette, LightColorPalette)) {
            val rungs = NxSurfaceLevel.entries.map { it.color(palette) }
            assertEquals(rungs.size, rungs.toSet().size, "two levels resolved to one colour: $rungs")
        }
    }

    // --- the per-theme default a surface that names no opacity gets ---

    @Test
    fun `light body floor is opaque`() {
        assertEquals(1.0f, bodyFloor(dark = false))
    }

    @Test
    fun `dark body floor is a high but sub-opaque floor`() {
        val f = bodyFloor(dark = true)
        assertTrue(f > 0.85f && f <= 1f, "dark floor $f out of (0.85, 1]")
    }

    // --- Rule 3: adjacent levels carry a real tonal step in both themes ---

    @Test
    fun `adjacent levels separate by a perceptual step on the dark palette`() {
        assertLadderSeparation(DarkColorPalette)
    }

    @Test
    fun `adjacent levels separate by a perceptual step on the light palette`() {
        assertLadderSeparation(LightColorPalette)
    }

    private fun assertLadderSeparation(palette: NxColors) {
        val ladder = listOf(
            NxSurfaceLevel.Sunken, NxSurfaceLevel.Base,
            NxSurfaceLevel.Raised, NxSurfaceLevel.Floating,
        ).map { it.color(palette) }
        ladder.zipWithNext { a, b ->
            val d = kotlin.math.abs(lstar(a) - lstar(b))
            assertTrue(d >= MIN_DELTA_L, "adjacent levels separated by only DeltaL* $d (< $MIN_DELTA_L)")
        }
    }

    private companion object {
        const val MIN_DELTA_L = 1.0f

        // CIELAB L* from relative luminance -- a perceptual step, not raw luminance
        // (raw luminance compresses near black where the dark ladder lives).
        fun lstar(c: Color): Float {
            val y = c.luminance().toDouble()
            val l = if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
            return l.toFloat()
        }
    }
}
