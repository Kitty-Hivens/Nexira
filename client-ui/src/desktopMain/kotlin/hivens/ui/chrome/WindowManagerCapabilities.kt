package hivens.ui.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import java.awt.EventQueue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The EWMH action names that map onto [WindowCapabilities]. Maximising is one
 * button, so it needs both axes: a window manager that allows only one of them
 * would leave the button doing half of what it shows.
 */
internal fun capabilitiesFromAllowedActions(actions: Collection<String>): WindowCapabilities = WindowCapabilities(
    move = "_NET_WM_ACTION_MOVE" in actions,
    resize = "_NET_WM_ACTION_RESIZE" in actions,
    maximize = "_NET_WM_ACTION_MAXIMIZE_HORZ" in actions && "_NET_WM_ACTION_MAXIMIZE_VERT" in actions,
    minimize = "_NET_WM_ACTION_MINIMIZE" in actions,
)

private val ACTION_NAMES = listOf(
    "_NET_WM_ACTION_MOVE",
    "_NET_WM_ACTION_RESIZE",
    "_NET_WM_ACTION_MAXIMIZE_HORZ",
    "_NET_WM_ACTION_MAXIMIZE_VERT",
    "_NET_WM_ACTION_MINIMIZE",
)

private val log: Logger = LoggerFactory.getLogger("WindowManager")

private val IS_LINUX = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

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
        if (!IS_LINUX) return@lazy true
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
 * The launcher window's [WindowCapabilities], kept current from what the window
 * manager publishes.
 *
 * On X11 a thread of its own holds an [X11Display] connection, finds the
 * window's X11 client window and re-reads `_NET_WM_ALLOWED_ACTIONS` every time
 * the window manager rewrites it. The first read happens on [attach] itself, so
 * the bar never shows a button for a moment before taking it away.
 */
class WindowCapabilitiesSource {
    var current by mutableStateOf(initial())
        private set

    @Volatile private var stopped = false

    fun attach(window: ComposeWindow) {
        if (!IS_LINUX || !WindowManagerProbe.allowsCustomFrame) {
            log.info("window capabilities: {}", current)
            return
        }
        val x = runCatching { X11Display.open() }.getOrNull()
        if (x == null) {
            log.info("window capabilities: no X server to ask, keeping {}", current)
            return
        }
        val started = runCatching { startWatching(x, window) }
            .onFailure { log.warn("Could not watch the window manager's allowed actions", it) }
            .getOrDefault(false)
        if (!started) x.close()
    }

    /** Lets the watcher go. It notices within [POLL_TIMEOUT_MS] and closes its connection. */
    fun detach() {
        stopped = true
    }

    /** True when a watcher took ownership of [x]. */
    private fun startWatching(x: X11Display, window: ComposeWindow): Boolean {
        val client = clientWindowOf(x, window.windowHandle)
        if (client == 0L) {
            log.warn(
                "window capabilities: could not find the launcher's X11 window from handle 0x{}, keeping {}",
                window.windowHandle.toString(16), current,
            )
            return false
        }
        val allowedActions = x.atom("_NET_WM_ALLOWED_ACTIONS")
        val actionAtoms = ACTION_NAMES.associateBy { x.atom(it) }.filterKeys { it != 0L }
        fun read(): WindowCapabilities? = x.longs(client, allowedActions)?.let { atoms ->
            capabilitiesFromAllowedActions(atoms.toList().mapNotNull { actionAtoms[it] })
        }

        x.watchProperties(client)
        // Until the window manager has written the property for this window, the
        // declaration it made on the root stands, and that declaration was yes.
        read()?.let { current = it }
        log.info("window capabilities: {} (X11 window 0x{})", current, client.toString(16))

        Thread({
            try {
                while (!stopped) {
                    val changes = x.awaitPropertyChanges(POLL_TIMEOUT_MS)
                    if (changes.none { (w, atom) -> w == client && atom == allowedActions }) continue
                    val next = read() ?: continue
                    EventQueue.invokeLater {
                        if (next != current) {
                            log.info("window capabilities <- WM: {}", next)
                            current = next
                        }
                    }
                }
            } catch (e: Throwable) {
                log.warn("Stopped watching the window manager's allowed actions", e)
            } finally {
                x.close()
            }
        }, "x11-allowed-actions").apply { isDaemon = true; start() }
        return true
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

    private fun initial(): WindowCapabilities =
        if (!IS_LINUX || WindowManagerProbe.allowsCustomFrame) WindowCapabilities.Everything else WindowCapabilities.Nothing

    private companion object {
        const val POLL_TIMEOUT_MS = 1000
        const val MAX_TREE_DEPTH = 16
    }
}
