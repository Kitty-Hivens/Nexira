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

    // --- level -> role mapping ---

    @Test
    fun `each level maps to its ladder role`() {
        assertEquals(FrostRole.SurfaceContainerLowest, NxSurfaceLevel.Sunken.role())
        assertEquals(FrostRole.SurfaceContainerLow, NxSurfaceLevel.Base.role())
        assertEquals(FrostRole.SurfaceContainer, NxSurfaceLevel.Raised.role())
        assertEquals(FrostRole.SurfaceContainerHigh, NxSurfaceLevel.Floating.role())
    }

    @Test
    fun `levels map to four distinct roles`() {
        val roles = NxSurfaceLevel.entries.map { it.role() }
        assertEquals(roles.size, roles.toSet().size)
    }

    // --- Rule 2: body floor is independent of the glass-intensity knob ---

    @Test
    fun `body alpha ignores glass intensity`() {
        // bodyAlpha has no intensity term by construction; it returns the floor verbatim.
        for (floor in listOf(0f, 0.5f, 0.92f, 1f)) {
            assertEquals(floor, bodyAlpha(floor))
        }
    }

    @Test
    fun `body alpha clamps out of range`() {
        assertEquals(0f, bodyAlpha(-0.3f))
        assertEquals(1f, bodyAlpha(1.4f))
    }

    @Test
    fun `coat alpha scales with glass intensity`() {
        assertEquals(0f, coatAlpha(0.6f, 0f))
        assertEquals(0.3f, coatAlpha(0.6f, 0.5f))
        assertEquals(0.6f, coatAlpha(0.6f, 1f))
    }

    // --- body floor per theme (Rule 4 phase 1: light is opaque) ---

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
        ).map { palette.ladderColor(it.role()) }
        ladder.zipWithNext { a, b ->
            val d = kotlin.math.abs(lstar(a) - lstar(b))
            assertTrue(d >= MIN_DELTA_L, "adjacent levels separated by only DeltaL* $d (< $MIN_DELTA_L)")
        }
    }

    private companion object {
        const val MIN_DELTA_L = 1.0f

        fun NxColors.ladderColor(role: FrostRole): Color = when (role) {
            FrostRole.Surface              -> surface
            FrostRole.SurfaceContainerLowest -> surfaceContainerLowest
            FrostRole.SurfaceContainerLow  -> surfaceContainerLow
            FrostRole.SurfaceContainer     -> surfaceContainer
            FrostRole.SurfaceContainerHigh -> surfaceContainerHigh
            else -> error("not a ladder role: $role")
        }

        // CIELAB L* from relative luminance -- a perceptual step, not raw luminance
        // (raw luminance compresses near black where the dark ladder lives).
        fun lstar(c: Color): Float {
            val y = c.luminance().toDouble()
            val l = if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
            return l.toFloat()
        }
    }
}
