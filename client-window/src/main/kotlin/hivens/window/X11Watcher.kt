package hivens.window

import hivens.core.platform.OS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The EWMH action names that map onto [WindowActions]. Maximising is one
 * button, so it needs both axes: a window manager that allows only one of them
 * would leave the button doing half of what it shows.
 */
internal fun actionsFromAllowedActions(actions: Collection<String>): WindowActions = WindowActions(
    move = "_NET_WM_ACTION_MOVE" in actions,
    resize = "_NET_WM_ACTION_RESIZE" in actions,
    maximize = "_NET_WM_ACTION_MAXIMIZE_HORZ" in actions && "_NET_WM_ACTION_MAXIMIZE_VERT" in actions,
    minimize = "_NET_WM_ACTION_MINIMIZE" in actions,
)

/**
 * Whether the window manager says, in `_NET_WM_STATE`, that the window is
 * maximised. Both axes, for the same reason as the maximise button's action.
 */
internal fun maximizedFromState(states: Collection<String>): Boolean =
    "_NET_WM_STATE_MAXIMIZED_VERT" in states && "_NET_WM_STATE_MAXIMIZED_HORZ" in states

private val STATE_NAMES = listOf("_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ")

private val ACTION_NAMES = listOf(
    "_NET_WM_ACTION_MOVE",
    "_NET_WM_ACTION_RESIZE",
    "_NET_WM_ACTION_MAXIMIZE_HORZ",
    "_NET_WM_ACTION_MAXIMIZE_VERT",
    "_NET_WM_ACTION_MINIMIZE",
)

private val log: Logger = LoggerFactory.getLogger("WindowManager")

/**
 * What the window manager says about letting windows draw their own frames,
 * asked once, before the window exists.
 *
 * On X11 the question is whether the window manager lists
 * `_NET_WM_ALLOWED_ACTIONS` in `_NET_SUPPORTED`, which is its promise to tell each
 * window what the user may do with it. Every floating window manager the
 * launcher has been checked against makes it (KWin, Mutter, Xfwm, Openbox).
 * Hyprland does not: it lists maximise and move/resize atoms it never acts on
 * for X11 clients, and no allowed actions at all, which is the honest half of
 * what it publishes.
 *
 * Windows and macOS always allow a frame of the app's own. With no X server to
 * ask, which a JVM on Linux only meets once it runs on native Wayland, the
 * answer stays yes, as it was before anyone asked.
 */
object WindowManagerProbe {
    val allowsCustomFrame: Boolean by lazy {
        if (!OS.isLinux) return@lazy true
        val declares = runCatching {
            X11Display.open()?.use { x ->
                val supported = x.longs(x.root, x.atom("_NET_SUPPORTED")) ?: longArrayOf()
                val allowed = x.atom("_NET_WM_ALLOWED_ACTIONS")
                allowed != 0L && allowed in supported
            }
        }.onFailure { log.warn("Could not ask the window manager what it supports", it) }.getOrNull()
        log.info("window manager: declares allowed actions={}", declares ?: "no X server to ask")
        declares ?: true
    }
}

/**
 * What an X11 window manager publishes about the launcher's window, kept current:
 * `_NET_WM_ALLOWED_ACTIONS` as [actions], and whether `_NET_WM_STATE` says the
 * window is maximised, handed to [onMaximized] from the watcher's own thread.
 *
 * A thread of its own holds an [X11Display] connection, finds the window's X11
 * client window and re-reads both properties every time the window manager
 * rewrites them, which it does for a change of its own as much as for one the
 * launcher asked for. The first read happens in [start] itself, so the bar never
 * shows a button for a moment before taking it away.
 */
internal class X11Watcher private constructor(
    private val x: X11Display,
    val clientWindow: Long,
    private val onMaximized: (Boolean) -> Unit,
) : AutoCloseable {
    private val _actions = MutableStateFlow(WindowActions.Everything)
    val actions: StateFlow<WindowActions> = _actions.asStateFlow()

    private var maximized = false

    @Volatile private var stopped = false

    /** Lets the watcher go. It notices within [POLL_TIMEOUT_MS] and closes its connection. */
    override fun close() {
        stopped = true
    }

    private fun run() {
        val client = clientWindow
        val allowedActions = x.atom("_NET_WM_ALLOWED_ACTIONS")
        val actionAtoms = ACTION_NAMES.associateBy { x.atom(it) }.filterKeys { it != 0L }
        fun read(): WindowActions? = x.longs(client, allowedActions)?.let { atoms ->
            actionsFromAllowedActions(atoms.toList().mapNotNull { actionAtoms[it] })
        }
        val netWmState = x.atom("_NET_WM_STATE")
        val stateAtoms = STATE_NAMES.associateBy { x.atom(it) }.filterKeys { it != 0L }
        // A window the window manager has written no state for has none.
        fun readMaximized(): Boolean = maximizedFromState(
            x.longs(client, netWmState)?.toList().orEmpty().mapNotNull { stateAtoms[it] },
        )

        x.watchProperties(client)
        // Until the window manager has written the property for this window, the
        // declaration it made on the root stands, and that declaration was yes.
        read()?.let { _actions.value = it }
        maximized = readMaximized()
        onMaximized(maximized)
        log.info("window capabilities: {}, maximized={} (X11 window 0x{})", actions.value, maximized, client.toString(16))

        Thread({
            try {
                while (!stopped) {
                    val changes = x.awaitPropertyChanges(POLL_TIMEOUT_MS).filter { (w, _) -> w == client }
                    if (changes.any { (_, atom) -> atom == allowedActions }) {
                        read()?.let { next ->
                            if (next != _actions.value) {
                                log.info("window capabilities <- WM: {}", next)
                                _actions.value = next
                            }
                        }
                    }
                    if (changes.any { (_, atom) -> atom == netWmState }) {
                        val next = readMaximized()
                        if (next != maximized) {
                            log.info("window state <- WM: maximized={}", next)
                            maximized = next
                            onMaximized(next)
                        }
                    }
                }
            } catch (e: Throwable) {
                log.warn("Stopped watching the window manager's allowed actions", e)
            } finally {
                x.close()
            }
        }, "x11-allowed-actions").apply { isDaemon = true; start() }
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 1000
        private const val MAX_TREE_DEPTH = 16

        /**
         * A watcher for the window whose toolkit handle is [handle], or null where
         * there is no X server to ask or the window cannot be found in it.
         */
        fun start(handle: Long, onMaximized: (Boolean) -> Unit): X11Watcher? {
            val x = runCatching { X11Display.open() }.getOrNull()
            if (x == null) {
                log.info("window capabilities: no X server to ask")
                return null
            }
            val watcher = runCatching {
                val client = clientWindowOf(x, handle)
                if (client == 0L) {
                    log.warn("window capabilities: could not find the launcher's X11 window from handle 0x{}", handle.toString(16))
                    null
                } else {
                    X11Watcher(x, client, onMaximized).also { it.run() }
                }
            }.onFailure { log.warn("Could not watch the window manager's allowed actions", it) }.getOrNull()
            if (watcher == null) x.close()
            return watcher
        }

        /**
         * The client window, in ICCCM terms, for [handle]: the window carrying
         * WM_STATE, which the window manager puts on exactly the windows it manages.
         *
         * [handle] is whatever X window the toolkit reports for the frame, and that is
         * not the same window everywhere. Under Openbox it is the window manager's own
         * decoration window, which sits above the client, while a canvas under the
         * client sits below it. So the search goes the way `XmuClientWindow` does it,
         * the window itself and then down through its children, and then up through
         * its ancestors for the case where it started below.
         */
        private fun clientWindowOf(x: X11Display, handle: Long): Long {
            if (handle == 0L) return 0L
            val wmState = x.atom("WM_STATE")
            if (wmState == 0L) return 0L
            val below = ArrayDeque(listOf(handle to 0))
            while (below.isNotEmpty()) {
                val (w, depth) = below.removeFirst()
                if (x.has(w, wmState)) return w
                if (depth < MAX_TREE_DEPTH) x.childrenOf(w).forEach { below.addLast(it to depth + 1) }
            }
            var w = x.parentOf(handle)
            repeat(MAX_TREE_DEPTH) {
                if (w == 0L || w == x.root) return 0L
                if (x.has(w, wmState)) return w
                w = x.parentOf(w)
            }
            return 0L
        }
    }
}
