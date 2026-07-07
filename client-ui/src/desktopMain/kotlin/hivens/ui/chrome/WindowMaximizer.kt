package hivens.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Work area of [gc] -- the monitor's bounds minus its screen insets (taskbar /
 * panel / menu bar + dock). AWT user space is numerically the Compose Dp value
 * 1:1 here (density scales the render, never the frame geometry), so these ints
 * are the window bounds directly, with no px<->dp conversion. A Wayland peer can
 * report a non-null gc with zero bounds before the surface negotiates; fall back
 * to the toolkit screen size then.
 */
fun screenWorkArea(gc: GraphicsConfiguration?): Rectangle {
    val tk = Toolkit.getDefaultToolkit()
    val b = gc?.bounds
    if (gc == null || b == null || b.width <= 0 || b.height <= 0) {
        // Degenerate (null gc, or a Wayland peer before the surface negotiates):
        // fall back to the primary screen, but still minus its insets when a gc is
        // available, so a maximize taken here never covers the taskbar.
        val s = tk.screenSize
        val i = gc?.let { tk.getScreenInsets(it) }
        return if (i != null) Rectangle(i.left, i.top, s.width - i.left - i.right, s.height - i.top - i.bottom)
        else Rectangle(0, 0, s.width, s.height)
    }
    val i = tk.getScreenInsets(gc)
    return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
}

/**
 * Deterministic maximize / restore for the undecorated (custom-chrome) window.
 *
 * AWT's MAXIMIZED_BOTH on an undecorated frame is unusable on Windows: the
 * extendedState read-back is racy so the caption glyph desyncs, and Compose
 * never sets `maximizedBounds`, so the frame covers the taskbar and snaps to the
 * primary monitor. So we own the maximize instead of routing through
 * [androidx.compose.ui.window.WindowPlacement.Maximized]: [maximized] is the
 * single source of truth for the glyph and the drag / resize gates, and we size
 * the frame to the current monitor's work area via [screenWorkArea]. Placement
 * stays Floating throughout -- Compose never runs the broken path -- and a direct
 * setBounds is resynced back into WindowState by Compose's own component
 * listeners, so the synthetic resize grips keep tracking the real geometry.
 *
 * Every method runs on the AWT EDT (all Compose click / effect bodies do), and
 * the bounds change is kept synchronous with the [maximized] flip -- that
 * synchrony is what removes the desync, so neither half is deferred to invokeLater.
 */
class WindowMaximizer(initiallyMaximized: Boolean) {
    var maximized by mutableStateOf(initiallyMaximized)
        private set

    private var window: ComposeWindow? = null
    // The floating frame to return to. Null until the first maximize captures it:
    // the window can open already-maximized, so the first restore has no prior
    // frame and falls back to [defaultRestoreBounds].
    private var restoreBounds: Rectangle? = null

    fun attach(w: ComposeWindow) { window = w }

    fun maximize() {
        val w = window ?: return
        if (!maximized) restoreBounds = Rectangle(w.bounds)
        w.bounds = screenWorkArea(w.graphicsConfiguration)
        maximized = true
    }

    fun restore() {
        val w = window ?: return
        w.bounds = restoreBounds ?: defaultRestoreBounds(w)
        maximized = false
    }

    fun toggle() { if (maximized) restore() else maximize() }

    /**
     * Un-maximize for a title-bar drag: restore the floating size but re-anchor it
     * so the cursor stays proportionally over the bar, matching the OS. The caller
     * passes the absolute screen cursor and re-reads `window.location` afterward, so
     * the delta-drag continues from the restored frame.
     */
    fun unmaximizeUnderCursor(mouseX: Int, mouseY: Int) {
        val w = window ?: return
        if (!maximized) return
        val target = restoreBounds ?: defaultRestoreBounds(w)
        val cur = w.bounds
        val ratioX = if (cur.width > 0) ((mouseX - cur.x).toFloat() / cur.width).coerceIn(0f, 1f) else 0.5f
        val newX = mouseX - (ratioX * target.width).toInt()
        // The bar sits at the maximized top (cur.y == work-area top); keeping that
        // y leaves it under the cursor.
        w.bounds = Rectangle(newX, cur.y, target.width, target.height)
        maximized = false
    }

    // Centered ~0.8x of the current work area, floored at the frame's minimum so
    // the native peer does not clamp a programmatic restore.
    private fun defaultRestoreBounds(w: ComposeWindow): Rectangle {
        val area = screenWorkArea(w.graphicsConfiguration)
        val width = (area.width * 0.8f).toInt().coerceAtLeast(w.minimumSize.width)
        val height = (area.height * 0.8f).toInt().coerceAtLeast(w.minimumSize.height)
        return Rectangle(area.x + (area.width - width) / 2, area.y + (area.height - height) / 2, width, height)
    }
}

val LocalWindowMaximizer = staticCompositionLocalOf<WindowMaximizer?> { null }
