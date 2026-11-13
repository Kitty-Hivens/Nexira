package hivens.window

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one owner of a launcher window's state, and the one way to change it. Every
 * OS window the launcher opens gets one: the main window and the console alike.
 *
 * [status] is what the system says, from one source per fact. Maximised is the
 * word of whoever decides it on this system: Windows through WM_SIZE where the
 * app's frame is the system's (see [Win32Frame]), an X11 window manager through
 * `_NET_WM_STATE` (see [X11Watcher]), and the frame's extended state elsewhere,
 * on macOS and wherever nothing better answers. Minimised is the frame's
 * extended state everywhere: every system tells AWT that one directly. The
 * launcher never sets either itself, nor infers them from how big the frame is:
 * a click before the system answers the last one would read its own guess, and a
 * frame that merely fills the screen is not maximised.
 *
 * [focused] and [size] are apart from [status]: they change by the dozen in a
 * minute and say nothing about which state the window is in, so whatever follows
 * the state (the caption buttons, the session record) is not woken by them.
 * Focus is the toolkit's focus events, and the size is the frame's own.
 *
 * The commands go to the frame, and the system answers through [status]. The UI
 * keeps no view of its own: Compose's `WindowState` follows the frame on its own,
 * and is only where the window is created.
 *
 * Leaving the maximised state hands the frame back at the normal size the system
 * remembers for it. Until the window has been restored once, that is the work
 * area, since it is born there (see [initialWindowSize]), so the first restore
 * would look exactly like a maximised window. That first restore is given
 * [firstRestore], the frame the window had when it was last closed (see
 * [WindowSessionRecorder]), or [RESTORE_WINDOW_SIZE] where nothing was saved, and
 * from then on the system remembers a real normal size. Windows offers no way to
 * set that size while the frame stays maximised: a reshape of a zoomed frame
 * drops the maximised bit. A window manager that does not allow the window to be
 * resized owns the geometry outright and is never handed a size.
 *
 * Everything here runs on the AWT event thread, the commands included. A system
 * that reports from a thread of its own is heard on the event thread.
 *
 * With `-Dnexira.puppet.port` set, every event dumps the full window state under
 * the `WINDBG` tag, so a run can be correlated line by line against the system's
 * own view.
 */
class WindowHost internal constructor(
    internal val frame: FrameOps,
    private val backend: Backend,
    private val firstRestore: Rectangle?,
) : AutoCloseable {
    private var frameState = frame.extendedState

    /** What the system's own source says about maximised, or null before it has said anything. */
    @Volatile private var reported: Boolean? = null

    private val _status = MutableStateFlow(compute())
    val status: StateFlow<WindowStatus> = _status.asStateFlow()

    private val _focused = MutableStateFlow(frame.focused)

    /** Whether the window holds the keyboard focus. */
    val focused: StateFlow<Boolean> = _focused.asStateFlow()

    private val _size = MutableStateFlow(sizeOf(frame.bounds))

    /** The frame's size, in AWT user space. */
    val size: StateFlow<WindowSize> = _size.asStateFlow()

    val actions: StateFlow<WindowActions> get() = backend.actions

    /** Whether the system's own frame moves and resizes the window. */
    val nativeFrame: Boolean get() = backend.nativeFrame

    /** Where a press moves the window, for the system's frame; null without one. */
    val captions: CaptionRegions? get() = backend.captions

    /** Whether the toolkit can maximise a frame here at all. */
    val maximizeSupported: Boolean = frame.maximizeSupported

    /** True while a window born maximised has not left it yet and been given a normal size. */
    private var firstRestorePending = false

    private val statusListeners = CopyOnWriteArrayList<(WindowStatus) -> Unit>()
    private val removals = ArrayList<() -> Unit>()

    internal fun start() {
        removals += frame.onStateChanged { old, new ->
            dump("frame-state old=${decode(old)} new=${decode(new)}")
            frameState = frame.extendedState
            publish("frame")
        }
        removals += frame.onFocusChanged { gained -> _focused.value = gained }
        removals += frame.onGeometryChanged { _size.value = sizeOf(frame.bounds) }
        backend.start { maximized ->
            reported = maximized
            frame.later { publish("system") }
        }
        frameState = frame.extendedState
        _status.value = compute()
        _focused.value = frame.focused
        _size.value = sizeOf(frame.bounds)
        captions?.resizable = actions.value.resize
        // Only a window born maximised has a normal size nobody chose. One that
        // opened as a window already has the size it was left at, and the system
        // remembers whatever it is moved to after that.
        firstRestorePending = _status.value.maximized
        log.info("window: maximize support={}, native frame={}, initial {}", maximizeSupported, nativeFrame, _status.value)
        dump("attach")
    }

    fun maximize() {
        val _ = runCatching { frame.setMaximizedBounds(frame.workArea) }
        log.info("window state -> system: requesting maximize")
        dump("cmd maximize")
        frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
    }

    fun restore() {
        log.info("window state -> system: requesting restore")
        dump("cmd restore")
        frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
    }

    // Decided by what the system has said, so a click before it answers the last
    // one repeats that request instead of sending the opposite.
    fun toggleMaximize() {
        if (_status.value.maximized) restore() else maximize()
    }

    fun minimize() {
        frame.extendedState = frame.extendedState or Frame.ICONIFIED
    }

    fun unminimize() {
        if (frame.extendedState and Frame.ICONIFIED != 0) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
    }

    /** Brings the window to the front and asks for the focus, the way a second launch does (see [FrameOps.raise]). */
    fun raise() {
        frame.raise()
    }

    /** Hands a drag of the caption to the window manager. False leaves the move to the caller. */
    fun beginMove(): Boolean = backend.beginMove()

    /** Hands a drag of [edge] to the window manager. False leaves the resize to the caller. */
    fun beginResize(edge: WindowEdge): Boolean = backend.beginResize(edge)

    /**
     * Un-maximise for a caption drag the caller moves by hand: request a restore,
     * then re-anchor so the pointer stays at the same share of the bar's width.
     * On the first restore the normal size is given right here rather than after
     * the event, so the drag carries the window it will end up with. The restored
     * bounds settle asynchronously on some systems, so this reads what it can and
     * the caller re-reads the frame's location afterward.
     */
    fun unmaximizeUnderCursor(pointerX: Int, pointerY: Int) {
        if (!_status.value.maximized) return
        val cur = frame.bounds
        val first = firstRestorePending && actions.value.resize
        firstRestorePending = false
        restore()
        val restored = if (first) firstRestoreBounds() else frame.bounds
        val ratioX = if (cur.width > 0) ((pointerX - cur.x).toFloat() / cur.width).coerceIn(0f, 1f) else 0.5f
        frame.bounds = Rectangle(pointerX - (ratioX * restored.width).toInt(), cur.y, restored.width, restored.height)
    }

    /**
     * Keeps the frame's minimum size at [design], cut to 90% of the screen the
     * window is on, and works it out again when the window crosses to another
     * screen. A minimum bigger than a small screen would leave a window that
     * cannot be made to fit it.
     */
    fun keepMinimumSize(design: Dimension) {
        var screen = frame.screenId
        fun apply() {
            val bounds = frame.screenBounds
            val size = if (bounds != null) Dimension(bounds.width, bounds.height) else Toolkit.getDefaultToolkit().screenSize
            val safe = computeSafeWindowMinSize(design.width, design.height, size)
            frame.later { frame.setMinimumSize(safe) }
        }
        removals += frame.onGeometryChanged {
            val now = frame.screenId
            if (now != screen) {
                screen = now
                apply()
            }
        }
        apply()
    }

    /** Calls [listener] with every change of [status], on the event thread. Returns the removal. */
    internal fun onStatusChanged(listener: (WindowStatus) -> Unit): () -> Unit {
        statusListeners += listener
        return { statusListeners -= listener }
    }

    override fun close() {
        removals.forEach { it() }
        removals.clear()
        statusListeners.clear()
        backend.close()
    }

    private fun compute() = WindowStatus(
        maximized = reported ?: isMax(frameState),
        minimized = frameState and Frame.ICONIFIED != 0,
    )

    private fun sizeOf(bounds: Rectangle) = WindowSize(bounds.width, bounds.height)

    /**
     * Publishes the state the sources say now, and says what it decided from.
     *
     * The numbers go in the line because this is the only timestamped signal the
     * launcher produces when the window's geometry moves under it. A tear across
     * the lower part of the window after a resume once left exactly one line in
     * the log saying the window had stopped being maximised, with nothing to say
     * whether the frame had really shrunk, whether the work area had grown, or
     * whether the two had simply stopped agreeing. A change is rare: a handful in
     * an hour of ordinary use.
     */
    private fun publish(source: String) {
        val previous = _status.value
        val next = compute()
        if (next == previous) return
        _status.value = next
        val b = frame.bounds
        val wa = frame.workArea
        log.info(
            "window state <- {}: maximized={} minimized={} (ext={} bounds={}x{} at {},{} work={}x{} at {},{})",
            source, next.maximized, next.minimized, decode(frameState),
            b.width, b.height, b.x, b.y, wa.width, wa.height, wa.x, wa.y,
        )
        // Resize after the one this same transition posts, or the system's would land on top of it.
        if (previous.maximized && !next.maximized && firstRestorePending) frame.later { giveRestoredSize() }
        statusListeners.forEach { it(next) }
    }

    /**
     * Gives the window its first normal size, on the first time it leaves
     * maximised. Waits for the next restore when the window went straight back
     * to maximised in between.
     */
    private fun giveRestoredSize() {
        if (!firstRestorePending || _status.value.maximized) return
        firstRestorePending = false
        if (!actions.value.resize) return
        val target = firstRestoreBounds()
        log.info("window state -> first restore, normal size {}x{} at {},{}", target.width, target.height, target.x, target.y)
        frame.bounds = target
        dump("first-restore-size")
    }

    private fun firstRestoreBounds(): Rectangle = firstRestore ?: restoredBounds(frame.workArea, RESTORE_WINDOW_SIZE)

    private fun isMax(s: Int) = (s and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH

    private fun dump(event: String) {
        if (!DEBUG) return
        val b = frame.bounds
        val wa = frame.workArea
        log.info(
            "WINDBG | $event | ext=${decode(frame.extendedState)} reported=$reported status=${_status.value} " +
                "bounds=[${b.x},${b.y} ${b.width}x${b.height}] work=[${wa.x},${wa.y} ${wa.width}x${wa.height}] " +
                "firstRestorePending=$firstRestorePending supported=$maximizeSupported",
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

    companion object {
        private val log: Logger = LoggerFactory.getLogger(WindowHost::class.java)
        private val DEBUG = System.getProperty("nexira.puppet.port") != null

        /**
         * Takes [window] over, on the AWT event thread, once it has a native
         * peer. [handle] is the peer's native window, [customFrame] says the
         * window is undecorated and draws its own frame, and [firstRestore] is
         * the normal frame a window born maximised gets on its first restore.
         */
        fun attach(window: Frame, handle: Long, customFrame: Boolean, firstRestore: Rectangle?): WindowHost =
            WindowHost(AwtFrameOps(window), Backend.choose(window, handle, customFrame), firstRestore).also { it.start() }
    }
}
