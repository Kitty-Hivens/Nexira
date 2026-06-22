package hivens.ui.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure resolution of tiers -> layer lists and the Edge group -> atom expansion. */
class FrostTierTest {

    @Test
    fun flatIsSingleFill() {
        val layers = FrostTier.Flat.toLayers()
        assertEquals(1, layers.size)
        assertTrue(layers.single() is Fill)
    }

    @Test
    fun frostedIsBackdropFillEdge() {
        assertEquals(
            listOf(Backdrop::class, Fill::class, Edge::class),
            FrostTier.Frosted.toLayers().map { it::class },
        )
    }

    @Test
    fun heavyKeepsLayerOrder() {
        assertEquals(
            listOf(Backdrop::class, Fill::class, Wash::class, Edge::class, Texture::class),
            FrostTier.Heavy.toLayers().map { it::class },
        )
    }

    @Test
    fun edgeExpandsBackToFront() {
        val atoms = Edge(highlight = true, shadow = true, border = true, glow = true).toAtoms()
        assertEquals(
            listOf(EdgeGlow::class, EdgeShadow::class, EdgeHighlight::class, EdgeBorder::class),
            atoms.map { it::class },
        )
    }

    @Test
    fun edgeDropsDisabledAtoms() {
        assertEquals(
            listOf(EdgeHighlight::class),
            Edge(highlight = true, shadow = false, border = false, glow = false).toAtoms().map { it::class },
        )
    }

    @Test
    fun edgeDefaultIsHighlightPlusShadow() {
        assertEquals(
            listOf(EdgeShadow::class, EdgeHighlight::class),
            Edge().toAtoms().map { it::class },
        )
    }

    @Test
    fun edgeAtomsCarryGroupParams() {
        val border = Edge(border = true, borderWidthDp = 2f, borderAlpha = 0.3f, borderRole = FrostRole.Primary)
            .toAtoms().filterIsInstance<EdgeBorder>().single()
        assertEquals(2f, border.widthDp)
        assertEquals(0.3f, border.alpha)
        assertEquals(FrostRole.Primary, border.role)
    }
}
