package hivens.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic the editor's gestures run on a page: how fast a page moves under
 * a drag at its edge, and which axis a lattice that counts rows holds a drag to.
 */
class PageGestureGeometryTest {

    private val start = 0f
    private val end = 1000f
    private val zone = 50f
    private val max = 20f

    @Test
    fun `away from both edges the page does not move`() {
        assertEquals(0f, autoScrollStep(500f, start, end, zone, max))
        assertEquals(0f, autoScrollStep(51f, start, end, zone, max))
        assertEquals(0f, autoScrollStep(949f, start, end, zone, max))
    }

    @Test
    fun `deeper into the edge zone the page moves faster`() {
        val shallow = autoScrollStep(990f - 30f, start, end, zone, max)
        val deep = autoScrollStep(990f, start, end, zone, max)
        assertTrue(shallow > 0f && deep > shallow, "deeper should be faster: $shallow then $deep")
        assertTrue(autoScrollStep(10f, start, end, zone, max) < 0f, "the start edge moves the page back")
    }

    @Test
    fun `past the edge the page runs at full speed and no faster`() {
        assertEquals(max, autoScrollStep(5000f, start, end, zone, max))
        assertEquals(-max, autoScrollStep(-5000f, start, end, zone, max))
    }

    @Test
    fun `a page shorter than its two zones never moves under a drag`() {
        // Both zones would cover the whole page, and the page would scroll wherever
        // the widget was held.
        assertEquals(0f, autoScrollStep(10f, 0f, 90f, zone, max))
    }

    @Test
    fun `a lattice counting rows holds the row and lets the column run`() {
        // A 100 cell, no gutter, two lines. Dragged 700 right and 700 down.
        val (col, row) = gridDragCell(0, 0, 700f, 700f, 1f, 100f, 0f, columns = 2, transposed = true)
        assertEquals(7, col, "the column is the side that grows")
        assertEquals(1, row, "the row stays inside the two there are")

        val (c2, r2) = gridDragCell(0, 0, 700f, 700f, 1f, 100f, 0f, columns = 2)
        assertEquals(1, c2, "a lattice counting columns still holds the column")
        assertEquals(7, r2)
    }

    @Test
    fun `a lattice counting rows caps the row span`() {
        val (w, h) = gridResizeSpan(1, 1, 500f, 500f, 1f, 100f, 0f, columns = 2, transposed = true)
        assertEquals(6, w)
        assertEquals(2, h)
    }
}
