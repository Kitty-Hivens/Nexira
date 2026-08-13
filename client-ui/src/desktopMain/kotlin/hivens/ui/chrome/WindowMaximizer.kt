package hivens.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Work area of [gc] -- the monitor's bounds minus the screen insets the WM
 * reports (taskbar / panel / menu bar + dock). AWT user space is numerically the
 * Compose Dp value 1:1 here (density scales the render, never the frame geometry).
 * A Wayland peer can report a non-null gc with zero bounds before the surface
 * negotiates; fall back to the toolkit screen size then.
 */
fun screenWorkArea(gc: GraphicsConfiguration?): Rectangle {
    val tk = Toolkit.getDefaultToolkit()
    val b = gc?.bounds
    if (gc == null || b == null || b.width <= 0 || b.height <= 0) {
        val s = tk.screenSize
        val i = gc?.let { tk.getScreenInsets(it) }
        return if (i != null) Rectangle(i.left, i.top, s.width - i.left - i.right, s.height - i.top - i.bottom)
        else Rectangle(0, 0, s.width, s.height)
    }
    val i = tk.getScreenInsets(gc)
    return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
}

/**
 * Maximize / restore driven end-to-end by the window manager.
 *
 * The window never fakes its own maximized state. [maximized] is recomputed ONLY
 * from what the system actually did -- never optimistically when a button is
 * pressed. Two system-driven signals feed it, so it is correct on every WM:
 *   - the maximized state the WM declares, via [WindowStateListener] on the frame's
 *     extendedState (the EWMH `_NET_WM_STATE_MAXIMIZED` contract);
 *   - the frame actually filling the work area, via [java.awt.event.ComponentListener]
 *     on resize -- ground truth for a compositor that maximizes without setting the
 *     state atom. We read the geometry the WM produced; we never impose it.
 *
 * [maximize] / [restore] COMMAND the system directly via [Frame.setExtendedState]
 * (MAXIMIZED_BOTH), NOT through WindowState.placement, whose "apply only on change"
 * caching swallows the request whenever Compose's observed placement has drifted to
 * the target value already (a WM that maximizes without confirming the frame state
 * leaves placement out of sync, so the button would silently no-op). [supported]
 * asks the toolkit whether the WM supports the state at all; the caller hides the
 * button when not.
 *
 * With `-Dnexira.puppet.port` set, every event dumps the full window state (raw
 * extendedState, Compose placement, observed flag, bounds vs work area) under the
 * `WINDBG` tag, so a run can be correlated line-by-line against the WM's own view.
 */
class WindowMaximizer(private val state: WindowState) {
    /** Real WM-reported state. Recomputed only from system events, never on click. */
    var maximized by mutableStateOf(false)
        private set

    /** Whether the running WM supports programmatic maximize, per the toolkit. */
    var supported by mutableStateOf(true)
        private set

    private var window: ComposeWindow? = null
    private var stateListener: WindowStateListener? = null
    private var sizeListener: ComponentAdapter? = null

    fun attach(w: ComposeWindow) {
        window = w
        supported = Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH)
        refresh(w)
        val sl = WindowStateListener { e ->
            dump(w, "WM-state-event old=${decode(e.oldState)} new=${decode(e.newState)}")
            refresh(w)
        }
        val cl = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                dump(w, "resize-event")
                refresh(w)
            }
        }
        w.addWindowStateListener(sl)
        w.addComponentListener(cl)
        stateListener = sl
        sizeListener = cl
        log.info("window maximize: WM support={}, initial maximized={}", supported, maximized)
        dump(w, "attach")
    }

    fun detach() {
        stateListener?.let { window?.removeWindowStateListener(it) }
        sizeListener?.let { window?.removeComponentListener(it) }
        stateListener = null
        sizeListener = null
        window = null
    }

    private fun refresh(w: ComposeWindow) {
        val now = isMax(w.extendedState) || fillsWorkArea(w)
        if (now != maximized) {
            maximized = now
            log.info("window state <- WM reports maximized={}", maximized)
        }
    }

    private fun fillsWorkArea(w: ComposeWindow): Boolean {
        val wa = screenWorkArea(w.graphicsConfiguration)
        if (wa.width <= 0 || wa.height <= 0) return false
        val b = w.bounds
        return b.width >= wa.width * 0.95f && b.height >= wa.height * 0.95f
    }

    fun maximize() {
        val w = window ?: return
        runCatching { w.maximizedBounds = screenWorkArea(w.graphicsConfiguration) }
        log.info("window state -> WM: requesting maximize")
        dump(w, "cmd maximize PRE")
        w.extendedState = w.extendedState or Frame.MAXIMIZED_BOTH
        dump(w, "cmd maximize POST")
    }

    fun restore() {
        val w = window ?: return
        log.info("window state -> WM: requesting restore")
        dump(w, "cmd restore PRE")
        w.extendedState = w.extendedState and Frame.MAXIMIZED_BOTH.inv()
        dump(w, "cmd restore POST")
    }

    // Decide off the window's real state at click time, not the possibly-lagging
    // observed flag, so a fast click can't act on a stale value.
    fun toggle() {
        val w = window ?: return
        if (isMax(w.extendedState) || fillsWorkArea(w)) restore() else maximize()
    }

    /**
     * Un-maximize for a title-bar drag on an undecorated frame: request a native
     * restore, then best-effort re-anchor so the cursor stays proportionally over
     * the bar. The restored bounds settle asynchronously on some WMs, so this reads
     * what it can and the drag loop re-reads `window.location` afterward.
     */
    fun unmaximizeUnderCursor(mouseX: Int, mouseY: Int) {
        val w = window ?: return
        if (!maximized) return
        val cur = w.bounds
        restore()
        val restored = w.bounds
        val ratioX = if (cur.width > 0) ((mouseX - cur.x).toFloat() / cur.width).coerceIn(0f, 1f) else 0.5f
        w.setLocation(mouseX - (ratioX * restored.width).toInt(), cur.y)
    }

    private fun isMax(s: Int) = (s and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH

    // ---- window-management diagnostics (WINDBG) --------------------------------
    // Enabled with -Dnexira.puppet.port so a debug run correlates the launcher's
    // view against the WM's. Off (and free) in production.

    private fun dump(w: ComposeWindow, event: String) {
        if (!DEBUG) return
        val es = w.extendedState
        val b = w.bounds
        val wa = screenWorkArea(w.graphicsConfiguration)
        log.info(
            "WINDBG | $event | ext=${decode(es)} placement=${state.placement} observed.max=$maximized " +
                "bounds=[${b.x},${b.y} ${b.width}x${b.height}] work=[${wa.x},${wa.y} ${wa.width}x${wa.height}] " +
                "fills=${fillsWorkArea(w)} supported=$supported",
        )
    }

    private fun decode(s: Int): String = buildString {
        if (s and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) {
            append("MAX_BOTH")
        } else {
            if (s and Frame.MAXIMIZED_HORIZ != 0) append("MAX_H")
            if (s and Frame.MAXIMIZED_VERT != 0) append("MAX_V")
        }
        if (s and Frame.ICONIFIED != 0) {
            if (isNotEmpty()) append("+")
            append("ICONIFIED")
        }
        if (isEmpty()) append("NORMAL")
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(WindowMaximizer::class.java)
        val DEBUG = System.getProperty("nexira.puppet.port") != null
    }
}

val LocalWindowMaximizer = staticCompositionLocalOf<WindowMaximizer?> { null }
