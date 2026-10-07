package hivens.tray

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * [TrayController] backed by libtray (`dev.hivens:libtray`), a Project-Panama-
 * only replacement for dorkbox/SystemTray. The host renders the menu via
 * DBusMenu on Linux / Shell_NotifyIcon on Windows / NSMenu on macOS -- we
 * publish the layout, the desktop draws it.
 *
 * The [State] machine distinguishes NOT_STARTED / INITIALIZING / READY / FAILED
 * so the close-request handler can prefer "hide to tray" over "quit" while
 * libtray is still bringing up its D-Bus / Shell_NotifyIcon registration (which
 * can take 0.5-3s). The volatile flags are read from the AWT thread while [init]
 * runs on an IO dispatcher.
 */
class LibTrayController : TrayController {

    private val logger = LoggerFactory.getLogger("TrayController")

    private enum class State { NOT_STARTED, INITIALIZING, READY, FAILED }

    @Volatile
    private var state: State = State.NOT_STARTED

    // Written by init on an IO thread and by the shell on the UI thread, read by
    // both, so each is published like the state above.
    @Volatile private var tray: Tray? = null
    @Volatile private var strings: TrayStrings? = null
    @Volatile private var appName: String = "Nexira"
    @Volatile private var unsubscribe: (() -> Unit)? = null

    /**
     * Installing a tray and taking it down happen under this, and [generation] says
     * whether the install still stands. Creating the tray takes seconds and runs
     * outside it, so a shutdown can land in the middle: it used to clear a handle
     * that init then assigned, leaving the icon registered with nothing to remove it.
     */
    private val lifecycle = Any()
    private var generation = 0

    @Volatile private var gameRunning: Boolean = false
    @Volatile private var gameServerName: String? = null

    override val isSupported: Boolean get() = state == State.READY
    override val canBeReady: Boolean get() = state == State.INITIALIZING || state == State.READY

    override var onShowWindow: (() -> Unit)? = null
    override var onShowConsole: (() -> Unit)? = null
    override var onExit: (() -> Unit)? = null

    private companion object {
        const val ID_SHOW    = "show"
        const val ID_CONSOLE = "console"
        const val ID_EXIT    = "exit"
    }

    override fun init(iconStream: InputStream, strings: TrayStrings, appName: String) {
        this.strings = strings
        this.appName = appName
        val mine = synchronized(lifecycle) {
            if (state != State.NOT_STARTED) return
            state = State.INITIALIZING
            generation
        }
        try {
            val iconBytes = iconStream.readAllBytes()
            val builder = TrayBuilder(
                title = appName,
                iconBytes = iconBytes,
                tooltip = "$appName | ${strings.statusIdle}",
                menu = buildMenu(strings),
            )
            val t = Tray.create(builder) ?: run {
                logger.warn("libtray Tray.create returned null -- no tray host reachable on this session")
                synchronized(lifecycle) { if (generation == mine) state = State.FAILED }
                return
            }
            synchronized(lifecycle) {
                if (generation != mine) {
                    // Shut down while the host was being reached: this tray is nobody's.
                    runCatching { t.close() }
                    return
                }
                tray = t
                unsubscribe = t.onEvent { event ->
                    when (event) {
                        // Left click -> restore window. Standard tray-icon behaviour.
                        is TrayEvent.Activated -> onShowWindow?.invoke()
                        is TrayEvent.MenuItemSelected -> dispatchMenu(event.id)
                        else -> Unit
                    }
                }
                state = State.READY
            }
            logger.info("Tray initialized via libtray (title='{}')", appName)
        } catch (t: Throwable) {
            synchronized(lifecycle) { if (generation == mine) state = State.FAILED }
            logger.error("Failed to initialize the tray", t)
        }
    }

    private fun dispatchMenu(id: String) {
        when (id) {
            ID_SHOW    -> onShowWindow?.invoke()
            ID_CONSOLE -> onShowConsole?.invoke()
            ID_EXIT    -> onExit?.invoke()
        }
    }

    override fun updateStrings(strings: TrayStrings) {
        this.strings = strings
        rebuildMenu()
        tray?.setTooltip(tooltip(strings))
    }

    override fun setGameStatus(running: Boolean, serverName: String?) {
        gameRunning = running
        gameServerName = serverName
        // Status surfaces in the tooltip only -- the menu is identical whether a
        // game runs or not, so there is nothing to rebuild here.
        val s = strings ?: return
        tray?.setTooltip(tooltip(s))
    }

    private fun tooltip(s: TrayStrings): String {
        val statusPart = when {
            gameRunning && gameServerName != null -> gameServerName!!
            gameRunning -> s.statusRunning
            else        -> s.statusIdle
        }
        return "$appName | $statusPart"
    }

    private fun rebuildMenu() {
        val s = strings ?: return
        tray?.setMenu(buildMenu(s))
    }

    private fun buildMenu(s: TrayStrings): TrayMenu = TrayMenu(
        listOf(
            TrayMenuItem.Standard(id = ID_SHOW,    label = s.show),
            TrayMenuItem.Standard(id = ID_CONSOLE, label = s.console),
            TrayMenuItem.Separator,
            TrayMenuItem.Standard(id = ID_EXIT,    label = s.exit),
        ),
    )

    override fun shutdown() {
        synchronized(lifecycle) {
            generation++
            runCatching { unsubscribe?.invoke() }
            runCatching { tray?.close() }
            tray = null
            unsubscribe = null
            gameRunning = false
            gameServerName = null
            state = State.NOT_STARTED
        }
    }
}
