package hivens.ui.theme

import androidx.compose.ui.unit.dp
import hivens.ui.customization.StyleOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StyleSpecOverridesTest {

    @Test
    fun `null overrides return the same spec instance`() {
        assertSame(CelestiaStyle, CelestiaStyle.applyOverrides(null))
    }

    @Test
    fun `empty overrides leaves every field untouched`() {
        val out = CelestiaStyle.applyOverrides(StyleOverrides())
        assertEquals(CelestiaStyle.cardCorner, out.cardCorner)
        assertEquals(CelestiaStyle.cardBorder, out.cardBorder)
        assertEquals(CelestiaStyle.buttonCorner, out.buttonCorner)
        assertEquals(CelestiaStyle.animationMultiplier, out.animationMultiplier)
        assertEquals(CelestiaStyle.softGlowEnabled, out.softGlowEnabled)
        assertEquals(CelestiaStyle.cardSurface, out.cardSurface)
    }

    @Test
    fun `partial overrides only touch the named fields`() {
        val out = CelestiaStyle.applyOverrides(
            StyleOverrides(
                cardCornerDp        = 4f,
                animationMultiplier = 0.5f,
            ),
        )
        assertEquals(4.dp, out.cardCorner)
        assertEquals(0.5f, out.animationMultiplier)
        // Unchanged fields keep preset values
        assertEquals(CelestiaStyle.cardBorder, out.cardBorder)
        assertEquals(CelestiaStyle.buttonCorner, out.buttonCorner)
        assertEquals(CelestiaStyle.softGlowEnabled, out.softGlowEnabled)
    }

    @Test
    fun `softGlow override flips the boolean`() {
        val out = CelestiaStyle.applyOverrides(StyleOverrides(softGlowEnabled = false))
        assertEquals(false, out.softGlowEnabled)
        // Brut-base + soft-glow-on override should restore the glow
        val outBrut = BrutStyle.applyOverrides(StyleOverrides(softGlowEnabled = true))
        assertEquals(true, outBrut.softGlowEnabled)
    }
}
