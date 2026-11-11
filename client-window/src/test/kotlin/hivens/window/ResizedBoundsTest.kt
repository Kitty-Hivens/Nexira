package hivens.window

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

/** The frame a grip drag produces, measured from where the press was. */
class ResizedBoundsTest {

    private val start = Rectangle(100, 200, 1100, 720)

    private fun resize(edge: WindowEdge, dx: Int, dy: Int) = resizedBounds(start, edge, dx, dy, minWidth = 960, minHeight = 600)

    @Test
    fun `the bottom right corner grows the window by what the pointer moved`() {
        assertEquals(Rectangle(100, 200, 1460, 960), resize(WindowEdge.SE, 360, 240))
    }

    @Test
    fun `the left and top edges move the frame and keep the opposite edges in place`() {
        assertEquals(Rectangle(50, 150, 1150, 770), resize(WindowEdge.NW, -50, -50))
    }

    @Test
    fun `a side stops at the minimum with its opposite edge where it was`() {
        assertEquals(Rectangle(100, 200, 960, 600), resize(WindowEdge.SE, -500, -500))
        val shrunkFromTheLeft = resize(WindowEdge.NW, 500, 500)
        assertEquals(1200, shrunkFromTheLeft.x + shrunkFromTheLeft.width, "the right edge stays")
        assertEquals(920, shrunkFromTheLeft.y + shrunkFromTheLeft.height, "the bottom edge stays")
        assertEquals(Rectangle(240, 320, 960, 600), shrunkFromTheLeft)
    }

    @Test
    fun `an edge moves only along its own axis`() {
        assertEquals(Rectangle(100, 200, 1150, 720), resize(WindowEdge.E, 50, 300))
        assertEquals(Rectangle(100, 200, 1100, 770), resize(WindowEdge.S, 300, 50))
    }
}
