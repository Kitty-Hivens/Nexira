package hivens.ui.surface

import androidx.compose.ui.graphics.Color
import hivens.look.surface.api.PlateAnswer
import hivens.ui.theme.Backdrop
import hivens.ui.theme.Plane
import hivens.look.surface.nx.nxSurfaceAnswers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Depth is relative. What used to be four absolute levels put a card inside a section
 * one rung BELOW the section, a recess on dark and a lift on light, because each word
 * named a rung rather than a relation. The nx surface answers keep it so, and the rest
 * of what nx-ui drew before the surface axis: opaque bodies, and a bevelled edge on
 * everything but the page and chrome.
 */
class NxSurfaceTest {

    private val top = 4
    private val nx = nxSurfaceAnswers()

    private fun plane(kind: SurfaceKind, parentStep: Int) = nx.plane(kind, Plane(parentStep, Color.Gray, Backdrop.Page), top)

    private fun step(kind: SurfaceKind, parentStep: Int): Int = plane(kind, parentStep).contentDepth

    @Test
    fun `a card is one step above whatever holds it`() {
        for (parent in -1..6) {
            assertEquals(parent + 1, step(SurfaceKind.Card, parent))
        }
    }

    @Test
    fun `a card inside a panel is above the panel`() {
        val panel = step(SurfaceKind.Panel, 0)
        val card = step(SurfaceKind.Card, panel)
        assertTrue(card > panel)
    }

    @Test
    fun `a field steps toward the page`() {
        val panel = step(SurfaceKind.Panel, 0)
        assertEquals(panel - 1, step(SurfaceKind.Field, panel))
    }

    @Test
    fun `floating kinds sit at the top of the ladder or above their holder`() {
        for (kind in listOf(SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice)) {
            assertEquals(top, step(kind, 0))
            assertEquals(7, step(kind, 6))
        }
    }

    @Test
    fun `chrome adds no depth to what it holds, and is drawn in the step above it`() {
        assertEquals(2, step(SurfaceKind.Chrome, 2))
        assertEquals(3, plane(SurfaceKind.Chrome, 2).depth)
    }

    @Test
    fun `the page is step zero wherever it is asked for`() {
        assertEquals(0, step(SurfaceKind.Page, 3))
        assertEquals(0, plane(SurfaceKind.Page, 3).depth)
    }

    @Test
    fun `every body is opaque, and every plane but the page and chrome has an edge`() {
        for (kind in SurfaceKind.entries) {
            val answer = plane(kind, 1)
            assertEquals(1f, answer.opacity, "$kind")
            assertEquals(if (kind == SurfaceKind.Page || kind == SurfaceKind.Chrome) 0f else 1f, answer.edgeDp, "$kind")
            if (kind != SurfaceKind.Chrome) assertEquals(answer.depth, answer.contentDepth, "$kind")
        }
    }

    @Test
    fun `a widget's plate takes no edge where its node names none`() {
        assertEquals(PlateAnswer(null, 0f), nx.plate)
    }
}
