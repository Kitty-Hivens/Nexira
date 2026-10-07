package hivens.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's overlays used to be held off the rails by two constants, and both
 * of them were props. What is pinned here is that the gaps come from the pane
 * that was actually drawn, so a rail that folds, widens or narrows moves them.
 */
class PaneInsetsTest {

    private val density = Density(density = 1f)
    private val window = Rect(0f, 0f, 1600f, 1000f)

    @Test
    fun `the gaps are what the rails left`() {
        val pane = Rect(65f, 44f, 1335f, 1000f)
        assertEquals(PaneInsets(start = 65.dp, end = 265.dp), paneInsets(pane, window, density))
    }

    @Test
    fun `a folded rail gives the overlays its width back`() {
        // The whole point: the constant said 265 whatever the rail was doing, so a
        // collapsed one left the panels a rail short of the edge.
        val folded = Rect(65f, 44f, 1600f, 1000f)
        assertEquals(0.dp, paneInsets(folded, window, density).end)
    }

    @Test
    fun `a widened rail is followed rather than left behind`() {
        val wide = Rect(65f, 44f, 1200f, 1000f)
        assertEquals(400.dp, paneInsets(wide, window, density).end)
    }

    @Test
    fun `density is applied, so the gaps are the pane's and not the screen's`() {
        val pane = Rect(130f, 88f, 2670f, 2000f)
        val at2x = paneInsets(pane, Rect(0f, 0f, 3200f, 2000f), Density(density = 2f))
        assertEquals(PaneInsets(start = 65.dp, end = 265.dp), at2x)
    }

    @Test
    fun `nothing reported yet is the full frame`() {
        assertEquals(PaneInsets(0.dp, 0.dp), paneInsets(pane = null, host = window, density = density))
    }

    @Test
    fun `the overlays are given room back rather than dropped all at once`() {
        // Two rails wide enough to squeeze the pane out is a state a reader has to
        // be able to undo, and the undo lives in the overlays. They creep over the
        // rail instead of jumping: a threshold that dropped both gaps at a stroke
        // teleported the panel a reader was dragging, across the window and back,
        // on every pixel over the line.
        val squeezed = Rect(750f, 0f, 900f, 1000f)
        val insets = paneInsets(squeezed, window, density)
        assertEquals(750.dp, insets.start, "the start edge is left where the rail put it")
        assertEquals(650.dp, insets.end, "the end gap gives way, because the panels hang off it")
    }

    @Test
    fun `what is left between the gaps is never less than a panel`() {
        listOf(
            Rect(750f, 0f, 900f, 1000f),
            Rect(900f, 0f, 1000f, 1000f),
            Rect(0f, 0f, 20f, 1000f),
            Rect(1580f, 0f, 1600f, 1000f),
        ).forEach { pane ->
            val insets = paneInsets(pane, window, density)
            val left = 1600f - insets.start.value - insets.end.value
            assertTrue(left >= 200f - 0.001f, "pane $pane left only $left")
        }
    }

    @Test
    fun `a pane with room to spare keeps both gaps untouched`() {
        val just = Rect(700f, 0f, 1020f, 1000f)
        assertEquals(PaneInsets(start = 700.dp, end = 580.dp), paneInsets(just, window, density))
    }

    @Test
    fun `an unmeasured frame is the full frame, and not by accident`() {
        // The pane here is wide and offset, so nothing but the host guard can
        // produce a zero: the previous version used a 10 by 10 pane, which the old
        // threshold caught first and left the guard untested.
        assertEquals(PaneInsets(0.dp, 0.dp), paneInsets(Rect(100f, 0f, 500f, 10f), Rect.Zero, density))
    }

    @Test
    fun `a pane wider than its frame reads as no gap, not a negative one`() {
        // Half a layout pass, not a real overhang. padding() rejects a negative.
        val overhang = Rect(-20f, 0f, 1700f, 1000f)
        assertEquals(PaneInsets(0.dp, 0.dp), paneInsets(overhang, window, density))
    }

    @Test
    fun `the frame is not assumed to start at the window's origin`() {
        val host = Rect(100f, 50f, 1500f, 1000f)
        val pane = Rect(200f, 50f, 1400f, 1000f)
        assertEquals(PaneInsets(start = 100.dp, end = 100.dp), paneInsets(pane, host, density))
    }
}
