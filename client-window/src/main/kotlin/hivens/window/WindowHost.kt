package hivens.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState
import java.awt.EventQueue
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
 * The window never fakes its own maximized state. On X11 and XWayland
 * [maximized] is the EWMH `_NET_WM_STATE_MAXIMIZED` atoms as the window manager
 * writes them, read by [WindowCapabilitiesSource] over its own connection. The
 * frame's extendedState is not that: AWT sets it the moment the launcher asks,
 * before the window manager has answered, so clicks in quick succession read an
 * answer that had not come yet and sent the window manager contradicting
 * requests, and a request it dropped left the button showing the wrong state for
 * good. Where nothing watches the window, on Windows and macOS whose systems
 * change it at once, [maximized] is extendedState as [WindowStateListener]
 * reports it. It is never set when a button is pressed, and never inferred from
 * how big the frame is. A frame that merely fills the screen is not maximized,
 * and treating it as one left the button offering a restore the window manager
 * had nothing to do for, so the click went nowhere until the window was resized
 * by hand.
 *
 * [maximize] / [restore] COMMAND the system directly via [Frame.setExtendedState]
 * (MAXIMIZED_BOTH), NOT through WindowState.placement, whose "apply only on change"
 * caching swallows the request whenever Compose's observed placement has drifted to
 * the target value already (a WM that maximizes without confirming the frame state
 * leaves placement out of sync, so the button would silently no-op). [supported]
 * asks the toolkit whether the WM supports the state at all; the caller hides the
 * button when not.
 *
 * Leaving the maximized state hands the frame back at the normal size the window
 * manager remembers for it. Until the window has been restored once, that is the
 * work area, since it is born there (see [initialWindowSize]), so the first restore
 * would look exactly like a maximized window. That first restore is given
 * [firstRestore], the frame the window had when it was last closed (see
 * [WindowSessionWriter]), or [RESTORE_WINDOW_SIZE] where nothing was saved, and
 * from then on the window manager remembers a real normal size and every later
 * restore is left to it. Windows offers no way
 * to set that size while the frame stays maximized: a reshape of a zoomed frame
 * drops the maximized bit. A window manager that does not allow the window to be
 * resized (see [WindowCapabilities]) owns the geometry outright and is never
 * handed a size.
 *
 * With `-Dnexira.puppet.port` set, every event dumps the full window state (raw
 * extendedState, Compose placement, observed flag, bounds vs work area) under the
 * `WINDBG` tag, so a run can be correlated line-by-line against the WM's own view.
 */
class WindowMaximizer(
    private val state: WindowState,
    private val capabilities: WindowCapabilitiesSource,
    private val firstRestore: Rectangle? = null,
) {
    /** The frame's extendedState, recomputed only from system events, never on click. */
    private var frameMaximized by mutableStateOf(false)

    /** Real WM-reported state: the window manager's own word where it is watched, the frame's elsewhere. */
    val maximized: Boolean get() = capabilities.maximized ?: frameMaximized

    /** Whether the running WM supports programmatic maximize, per the toolkit. */
    var supported by mutableStateOf(true)
        private set

    /** True while a window born maximized has not left it yet and been given a normal size. */
    private var firstRestorePending = true

    private var window: ComposeWindow? = null
    private var stateListener: WindowStateListener? = null
    private var sizeListener: ComponentAdapter? = null

    fun attach(w: ComposeWindow) {
        window = w
        supported = Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH)
        // Only a window born maximized has a normal size nobody chose. One that
        // opened as a window already has the size it was left at, and the window
        // manager remembers whatever it is moved to after that.
        firstRestorePending = isMax(w.extendedState)
        refresh(w)
        val sl = WindowStateListener { e ->
            dump(w, "WM-state-event old=${decode(e.oldState)} new=${decode(e.newState)}")
            refresh(w)
            // Resize after the one this same transition posts, or the WM's would land on top of it.
            if (firstRestorePending && isMax(e.oldState) && !isMax(e.newState)) EventQueue.invokeLater { giveRestoredSize(w) }
        }
        w.addWindowStateListener(sl)
        stateListener = sl
        if (DEBUG) {
            val cl = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = dump(w, "resize-event")
            }
            w.addComponentListener(cl)
            sizeListener = cl
        }
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

    /**
     * Recomputes the flag, and says what it decided from.
     *
     * The numbers go in the line because this flag is the only timestamped signal
     * the launcher produces when the window's geometry moves under it, and it used
     * to record the conclusion and throw the evidence away. A tear across the lower
     * part of the window after a resume left exactly one line in the log saying the
     * window had stopped being maximized, with nothing to say whether the frame had
     * really shrunk, whether the work area had grown, or whether the two had simply
     * stopped agreeing. The full dump beside this carries all of it and is behind a
     * debug property, which is the wrong side of the fence for something that
     * happens on a laptop lid and not on demand.
     *
     * It costs a line per change, and a change is rare: a handful in an hour of
     * ordinary use, since it only fires when the answer actually moves.
     */
    private fun refresh(w: ComposeWindow) {
        val now = isMax(w.extendedState)
        if (now != frameMaximized) {
            frameMaximized = now
            val b = w.bounds
            val wa = screenWorkArea(w.graphicsConfiguration)
            log.info(
                "window state <- frame reports maximized={} (ext={} bounds={}x{} at {},{} work={}x{} at {},{})",
                frameMaximized, decode(w.extendedState),
                b.width, b.height, b.x, b.y,
                wa.width, wa.height, wa.x, wa.y,
            )
        }
    }

    /**
     * Gives the window its first normal size, on the first time it leaves
     * maximized. Waits for the next restore when the window went straight back
     * to maximized in between.
     */
    private fun giveRestoredSize(w: ComposeWindow) {
        if (window !== w || !firstRestorePending || isMax(w.extendedState)) return
        firstRestorePending = false
        if (!capabilities.current.resize) return
        val target = firstRestoreBounds(w)
        log.info(
            "window state -> first restore, normal size {}x{} at {},{}",
            target.width, target.height, target.x, target.y,
        )
        w.bounds = target
        dump(w, "first-restore-size")
    }

    fun maximize() {
        val w = window ?: return
        val _ = runCatching { w.maximizedBounds = screenWorkArea(w.graphicsConfiguration) }
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

    // Decided by what the window manager has said, so a click before it answers
    // the last one repeats that request instead of sending the opposite.
    fun toggle() {
        val w = window ?: return
        if (capabilities.maximized ?: isMax(w.extendedState)) restore() else maximize()
    }

    /**
     * Un-maximize for a title-bar drag on an undecorated frame: request a native
     * restore, then best-effort re-anchor so the cursor stays proportionally over
     * the bar. On the first restore the normal size is given right here rather
     * than after the event, so the drag carries the window it will end up with.
     * The restored bounds settle asynchronously on some WMs, so this reads what it
     * can and the drag loop re-reads `window.location` afterward.
     */
    fun unmaximizeUnderCursor(mouseX: Int, mouseY: Int) {
        val w = window ?: return
        if (!maximized) return
        val cur = w.bounds
        val first = firstRestorePending && capabilities.current.resize
        firstRestorePending = false
        restore()
        val restored = if (first) firstRestoreBounds(w) else w.bounds
        val ratioX = if (cur.width > 0) ((mouseX - cur.x).toFloat() / cur.width).coerceIn(0f, 1f) else 0.5f
        w.setBounds(mouseX - (ratioX * restored.width).toInt(), cur.y, restored.width, restored.height)
    }

    private fun firstRestoreBounds(w: ComposeWindow): Rectangle =
        firstRestore ?: restoredBounds(screenWorkArea(w.graphicsConfiguration), RESTORE_WINDOW_SIZE)

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
                "firstRestorePending=$firstRestorePending supported=$supported",
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
