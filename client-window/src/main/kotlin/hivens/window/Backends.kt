package hivens.window

import hivens.core.platform.OS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Frame

/**
 * The part of [WindowHost] that differs per system: whose word the window's
 * maximised state is, what the window manager allows, and who moves and resizes
 * the window when the user drags it.
 */
internal interface Backend : AutoCloseable {
    val actions: StateFlow<WindowActions>

    /** The caption the system's frame answers for, or null where the system draws no frame of the app's. */
    val captions: CaptionRegions?

    /** Whether the system's own frame moves and resizes the window (see [Win32Frame]). */
    val nativeFrame: Boolean

    /**
     * Starts listening. [report] hears whether the system says the window is
     * maximised, from whatever thread the system says it on. A backend that never
     * calls it leaves the frame's extended state as the answer.
     */
    fun start(report: (Boolean) -> Unit)

    /** Hands a drag of the caption to the window manager. False leaves it to the caller. */
    fun beginMove(): Boolean = false

    /** Hands a drag of [edge] to the window manager. False leaves it to the caller. */
    fun beginResize(edge: WindowEdge): Boolean = false

    override fun close() {}

    companion object {
        /**
         * The backend for this system. [customFrame] says the window is undecorated
         * and draws its own frame.
         */
        fun choose(window: Frame, handle: Long, customFrame: Boolean): Backend = when {
            OS.isWindows && customFrame -> Win32Backend(window, handle)
            OS.isLinux && WindowManagerProbe.allowsCustomFrame -> X11Backend(handle)
            // A window manager that declares no allowed actions promises nothing.
            OS.isLinux -> PlainBackend(WindowActions.Nothing)
            else -> PlainBackend(WindowActions.Everything)
        }
    }
}

/** Nothing but the frame: its extended state is the answer, and every drag is the caller's. */
internal class PlainBackend(actions: WindowActions) : Backend {
    override val actions: StateFlow<WindowActions> = MutableStateFlow(actions).asStateFlow()
    override val captions: CaptionRegions? = null
    override val nativeFrame = false
    override fun start(report: (Boolean) -> Unit) {}
}

/**
 * Windows, with the app's own frame: the system's frame through [Win32Frame],
 * which also reports maximised from WM_SIZE. Where the frame cannot be put in,
 * the window keeps the drawn one and the frame's extended state is the answer.
 */
internal class Win32Backend(private val window: Frame, private val handle: Long) : Backend {
    override val actions: StateFlow<WindowActions> = MutableStateFlow(WindowActions.Everything).asStateFlow()
    private val regions = CaptionRegions()
    override var nativeFrame = false
        private set
    override val captions: CaptionRegions? get() = regions.takeIf { nativeFrame }

    override fun start(report: (Boolean) -> Unit) {
        nativeFrame = Win32Frame.install(window, handle, regions, report)
    }
}

/**
 * X11 and XWayland under a window manager that declares what it allows: its
 * word on maximised and on the allowed actions, watched live, and drags handed
 * to it where it takes them (see [X11SystemDrag]).
 */
internal class X11Backend(private val handle: Long) : Backend {
    private val fallback = MutableStateFlow(WindowActions.Everything).asStateFlow()
    private var watcher: X11Watcher? = null
    override val actions: StateFlow<WindowActions> get() = watcher?.actions ?: fallback
    override val captions: CaptionRegions? = null
    override val nativeFrame = false

    override fun start(report: (Boolean) -> Unit) {
        watcher = X11Watcher.start(handle, report)
    }

    override fun beginMove(): Boolean = X11SystemDrag.begin(watcher?.clientWindow ?: 0L, X11SystemDrag.MOVE)

    override fun beginResize(edge: WindowEdge): Boolean = X11SystemDrag.begin(watcher?.clientWindow ?: 0L, edge.ewmh)

    override fun close() {
        watcher?.close()
    }
}
