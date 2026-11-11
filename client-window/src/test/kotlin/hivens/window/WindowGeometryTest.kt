package hivens.window

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size the window is born at, which decides what the user sees before
 * anything else.
 *
 * Compose draws one frame before showing the window, sized to this request, and
 * a request smaller than the window ends up being leaves the rest of the frame
 * as bare native surface -- white. Recorded on Hyprland at 120 fps: a 1100x720
 * request under a compositor that tiled the window full-screen showed white
 * around a correctly drawn corner for roughly 1.9 seconds. The work area showed
 * none, tiled or floating, which is why there is no window-manager branch here.
 */
class WindowGeometryTest {

    private val restore = Dimension(1100, 720)
    private val floor = Dimension(800, 600)

    @Test
    fun `the window is born at the work area`() {
        // 2560x1440 minus a 48px panel.
        assertEquals(Dimension(2560, 1392), initialWindowSize(Rectangle(0, 0, 2560, 1392), restore, floor))
    }

    @Test
    fun `a work area smaller than the shell can lay out still yields a usable window`() {
        assertEquals(
            floor,
            initialWindowSize(Rectangle(0, 0, 800, 480), restore, floor),
            "obeying a tiny work area would open a window the rails do not fit in",
        )
    }

    @Test
    fun `only the axis that is too small is raised`() {
        assertEquals(Dimension(1920, floor.height), initialWindowSize(Rectangle(0, 0, 1920, 400), restore, floor))
    }

    @Test
    fun `a work area a peer has not negotiated yet falls back to the restore size`() {
        // Wayland can report a zero-sized bounds before the surface settles;
        // guessing a window from that is worse than the size we already have.
        assertEquals(restore, initialWindowSize(Rectangle(0, 0, 0, 0), restore, floor))
        assertEquals(restore, initialWindowSize(Rectangle(0, 0, 1920, 0), restore, floor))
    }

    @Test
    fun `where the work area starts does not reach its size`() {
        // A panel on the left shifts the origin; the window is sized by extent,
        // and the desktop decides where it sits.
        assertEquals(Dimension(2000, 1400), initialWindowSize(Rectangle(560, 40, 2000, 1400), restore, floor))
    }

    @Test
    fun `the restored frame is the restore size centred on the work area`() {
        assertEquals(Rectangle(730, 336, 1100, 720), restoredBounds(Rectangle(0, 0, 2560, 1392), restore))
    }

    @Test
    fun `the restored frame is centred on the monitor it was restored on`() {
        // A second monitor to the right with a panel on top.
        assertEquals(
            Rectangle(2560 + 410, 40 + 160, 1100, 720),
            restoredBounds(Rectangle(2560, 40, 1920, 1040), restore),
        )
    }

    @Test
    fun `a small screen gets a restored frame that is visibly not maximised`() {
        assertEquals(Rectangle(128, 72, 1024, 576), restoredBounds(Rectangle(0, 0, 1280, 720), restore))
    }

    @Test
    fun `the restored frame sits inside the work area and short of it on both axes`() {
        for ((w, h) in listOf(800 to 600, 1024 to 768, 1280 to 720, 1366 to 768, 1920 to 1080, 3840 to 2160, 1001 to 1001)) {
            val wa = Rectangle(100, 50, w, h)
            val r = restoredBounds(wa, restore)
            assertTrue(wa.contains(r), "${w}x$h: $r outside $wa")
            assertTrue(r.width < wa.width && r.height < wa.height, "${w}x$h: $r reads as maximised")
        }
    }
}
