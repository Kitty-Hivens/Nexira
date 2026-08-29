package hivens.ui.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure resolution of a tier into its layer list. */
class FrostTierTest {

    @Test
    fun flatIsSingleFill() {
        val layers = FrostTier.Flat.toLayers()
        assertEquals(1, layers.size)
        assertTrue(layers.single() is Fill)
    }

    // Clear is the transparent tier: a lone glass fill, no body layer of its own
    // (NxSurface renders it bodiless).
    @Test
    fun clearIsSingleFill() {
        val layers = FrostTier.Clear.toLayers()
        assertEquals(1, layers.size)
        assertTrue(layers.single() is Fill)
    }

    @Test
    fun frostedIsBackdropFillCastShadow() {
        assertEquals(
            listOf(Backdrop::class, Fill::class, DropShadow::class),
            FrostTier.Frosted.toLayers().map { it::class },
        )
    }

    @Test
    fun heavyKeepsLayerOrder() {
        assertEquals(
            listOf(Backdrop::class, Fill::class, DropShadow::class),
            FrostTier.Heavy.toLayers().map { it::class },
        )
    }

    /**
     * Depth is cast from outside the plane, not painted inside it. The inner bevel
     * lightened a band along the top and darkened one along the bottom, which is
     * the fill changing value rather than light behaving, and the accent wash
     * tinted every heavy plane with a colour nothing asked for. Both kinds have
     * since been deleted for want of a single caller, so what this now pins is
     * that a preset carries nothing but a body, a blur and a cast shadow.
     */
    @Test
    fun presetsCarryOnlyBodyBlurAndCastShadow() {
        val allowed = setOf(Backdrop::class, Fill::class, DropShadow::class)
        for (tier in FrostTier.entries) {
            val kinds = tier.toLayers().map { it::class }
            assertTrue(
                kinds.all { it in allowed },
                "$tier carries something other than a body, a blur or a cast shadow: ${kinds.map { it.simpleName }}",
            )
        }
    }
}
