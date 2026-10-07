package hivens.ui.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Depth is relative. What used to be four absolute levels put a card inside a section
 * one rung BELOW the section, a recess on dark and a lift on light, because each word
 * named a rung rather than a relation.
 */
class NxSurfaceTest {

    private val top = 4

    @Test
    fun `a card is one step above whatever holds it`() {
        for (parent in -1..6) {
            assertEquals(parent + 1, surfaceStep(SurfaceKind.Card, parent, top))
        }
    }

    @Test
    fun `a card inside a panel is above the panel`() {
        val panel = surfaceStep(SurfaceKind.Panel, parentStep = 0, topStep = top)
        val card = surfaceStep(SurfaceKind.Card, parentStep = panel, topStep = top)
        assertTrue(card > panel)
    }

    @Test
    fun `a field steps toward the page`() {
        val panel = surfaceStep(SurfaceKind.Panel, parentStep = 0, topStep = top)
        assertEquals(panel - 1, surfaceStep(SurfaceKind.Field, panel, top))
    }

    @Test
    fun `floating kinds sit at the top of the ladder or above their holder`() {
        for (kind in listOf(SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice)) {
            assertEquals(top, surfaceStep(kind, parentStep = 0, topStep = top))
            assertEquals(7, surfaceStep(kind, parentStep = 6, topStep = top))
        }
    }

    @Test
    fun `chrome is glass and adds no depth`() {
        assertEquals(2, surfaceStep(SurfaceKind.Chrome, parentStep = 2, topStep = top))
    }

    @Test
    fun `the page is step zero wherever it is asked for`() {
        assertEquals(0, surfaceStep(SurfaceKind.Page, parentStep = 3, topStep = top))
    }
}
