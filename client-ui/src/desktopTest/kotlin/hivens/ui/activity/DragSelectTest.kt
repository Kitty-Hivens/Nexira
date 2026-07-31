package hivens.ui.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two decisions inside the drag gesture, apart from the pointer plumbing
 * that carries them. Both are the kind that look obviously right and are wrong
 * at an edge: a ramp that divides by a band larger than the viewport, and a
 * paint rule that toggles instead of painting.
 */
class DragSelectTest {

    private val viewport = 600

    @Test
    fun `away from both ends the list does not move`() {
        assertEquals(0f, edgeScrollRate(300f, viewport))
        assertEquals(0f, edgeScrollRate(120f, viewport))
        assertEquals(0f, edgeScrollRate(480f, viewport))
    }

    @Test
    fun `the rate grows as the pointer nears an end`() {
        val near = edgeScrollRate(80f, viewport)
        val nearer = edgeScrollRate(30f, viewport)
        val edge = edgeScrollRate(0f, viewport)
        assertTrue(near < 0f && nearer < near && edge <= nearer, "$near -> $nearer -> $edge")
        assertEquals(-1f, edge)
    }

    @Test
    fun `the two ends pull opposite ways`() {
        assertTrue(edgeScrollRate(10f, viewport) < 0f, "the top pulls towards the start")
        assertTrue(edgeScrollRate(590f, viewport) > 0f, "the bottom pulls towards the end")
    }

    @Test
    fun `past an end the rate is held at full rather than run away`() {
        assertEquals(-1f, edgeScrollRate(-40f, viewport))
        assertEquals(1f, edgeScrollRate(viewport + 40f, viewport))
    }

    @Test
    fun `a viewport shorter than two bands does not pull both ways at once`() {
        // Bands wider than half the viewport would overlap, and a point inside
        // both would answer to whichever branch was written first.
        val tiny = 100
        val middle = edgeScrollRate(50f, tiny)
        assertEquals(0f, middle, "the exact middle belongs to neither end")
        assertTrue(edgeScrollRate(10f, tiny) < 0f)
        assertTrue(edgeScrollRate(90f, tiny) > 0f)
    }

    @Test
    fun `a viewport with no height is not divided by`() {
        assertEquals(0f, edgeScrollRate(0f, 0))
    }

    @Test
    fun `the drag paints rather than toggles`() {
        // Begun on an unselected row it selects everything it crosses, including
        // rows already selected, so a path drawn back over itself is stable.
        assertTrue(paintValue(startSelected = false))
        assertTrue(!paintValue(startSelected = true))
    }
}
