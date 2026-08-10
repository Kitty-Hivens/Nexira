package hivens.ui.chrome

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private val restore = DpSize(1100.dp, 720.dp)

    @Test
    fun `the window is born at the work area`() {
        // 2560x1440 minus a 48px panel.
        assertEquals(
            DpSize(2560.dp, 1392.dp),
            initialWindowSize(Rectangle(0, 0, 2560, 1392), restore),
        )
    }

    @Test
    fun `a work area smaller than the shell can lay out still yields a usable window`() {
        assertEquals(
            DpSize(MIN_WINDOW_WIDTH_DP.dp, MIN_WINDOW_HEIGHT_DP.dp),
            initialWindowSize(Rectangle(0, 0, 800, 480), restore),
            "obeying a tiny work area would open a window the rails do not fit in",
        )
    }

    @Test
    fun `only the axis that is too small is raised`() {
        assertEquals(
            DpSize(1920.dp, MIN_WINDOW_HEIGHT_DP.dp),
            initialWindowSize(Rectangle(0, 0, 1920, 400), restore),
        )
    }

    @Test
    fun `a work area a peer has not negotiated yet falls back to the restore size`() {
        // Wayland can report a zero-sized bounds before the surface settles;
        // guessing a window from that is worse than the size we already have.
        assertEquals(restore, initialWindowSize(Rectangle(0, 0, 0, 0), restore))
        assertEquals(restore, initialWindowSize(Rectangle(0, 0, 1920, 0), restore))
    }

    @Test
    fun `where the work area starts does not reach its size`() {
        // A panel on the left shifts the origin; the window is sized by extent,
        // and the desktop decides where it sits.
        assertEquals(
            DpSize(2000.dp, 1400.dp),
            initialWindowSize(Rectangle(560, 40, 2000, 1400), restore),
        )
    }
}
