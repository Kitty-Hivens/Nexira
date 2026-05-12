package hivens.ui.tray

import dorkbox.systemTray.Menu
import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.Separator
import dorkbox.systemTray.SystemTray
import hivens.core.api.model.ServerProfile
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * System tray manager backed by dorkbox/SystemTray 4.4.
 *
 * All callbacks are invoked on the AWT thread by dorkbox.
 * Must be initialised once from Main.kt after Koin is ready.
 */
object TrayManager {

    private val logger = LoggerFactory.getLogger("TrayManager")

    private var tray: SystemTray? = null
    private var statusItem: MenuItem? = null
    private var serversSubmenu: Menu? = null
    private val serverItems = mutableListOf<MenuItem>()
    private var noServersItem: MenuItem? = null
    private var strings: Strings? = null

    /**
     * Lifecycle state of the tray. Distinguishes "init has not even started"
     * from "init is running but hasn't completed yet" — critical because
     * dorkbox's `SystemTray.get()` can stall for up to ~60s on Linux setups
     * with broken/missing GTK libraries before falling back to Swing. During
     * that window the user might close the launcher window expecting the
     * standard "minimize-to-tray" behavior; without this state we'd see
     * `isSupported == false` and call `exitApplication()` instead, killing
     * the launcher (and any game launch in progress).
     *
     * Volatile because [init] runs on Dispatchers.IO and the close-request
     * callbacks in Main.kt read state from the AWT thread.
     */
    enum class State { NOT_STARTED, INITIALIZING, READY, FAILED }

    @Volatile
    private var state: State = State.NOT_STARTED

    /**
     * True only when the tray is fully registered. Use this when a code
     * path needs to *act* on the tray right now (e.g. update menu items).
     */
    val isSupported: Boolean get() = state == State.READY

    /**
     * True when the tray either is ready or is still in the middle of
     * initialising. Use this for close-request handlers: if the tray
     * might still come up, prefer "hide to tray" over "exit application",
     * since the user's intent is "minimise" and we don't want to kill the
     * launcher because dorkbox's GTK probe is slow.
     */
    val canBeReady: Boolean get() = state == State.INITIALIZING || state == State.READY

    data class Strings(
        val statusIdle: String,
        val statusRunning: String,
        val show: String,
        val console: String,
        val servers: String,
        val noServers: String,
        val exit: String
    )

    // ── Callbacks ─────────────────────────────────────────────────────────

    var onShowWindow:   (() -> Unit)? = null
    var onExit:         (() -> Unit)? = null
    var onShowConsole:  (() -> Unit)? = null
    var onLaunchServer: ((ServerProfile) -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun init(iconStream: InputStream, strings: Strings) {
        this.strings = strings
        if (state != State.NOT_STARTED) return

        state = State.INITIALIZING
        try {
            SystemTray.DEBUG = false
            val t = SystemTray.get() ?: run {
                logger.warn("SystemTray not supported on this platform")
                state = State.FAILED
                return
            }
            tray = t
            // No setTooltip(): dorkbox itself warns tooltips are inconsistent
            // across platforms (KDE/AppIndicator ignores the value entirely
            // and shows the library's own class name "SystemTray"). The
            // .desktop StartupWMClass + window title carry identity better.
            t.setImage(iconStream)
            buildMenu(t.menu, strings)
            state = State.READY
            logger.info("TrayManager initialized ({})", t.javaClass.simpleName)
        } catch (t: Throwable) {
            state = State.FAILED
            logger.error("Failed to initialize TrayManager", t)
        }
    }

    private fun buildMenu(menu: Menu, s: Strings) {
        // Status line — disabled, acts as label
        val status = MenuItem(s.statusIdle)
        status.setEnabled(false)
        menu.add(status)
        statusItem = status

        menu.add(Separator())

        menu.add(MenuItem(s.show) { onShowWindow?.invoke() })
        menu.add(MenuItem(s.console) { onShowConsole?.invoke() })

        menu.add(Separator())

        // Servers submenu
        val sub = Menu(s.servers)
        val noServers = MenuItem(s.noServers)
        noServers.setEnabled(false)
        sub.add(noServers)
        noServersItem = noServers
        serversSubmenu = sub
        menu.add(sub)

        menu.add(Separator())

        menu.add(MenuItem(s.exit) { onExit?.invoke() })
    }

    // ── State updates ──────────────────────────────────────────────────────

    fun updateServers(servers: List<ServerProfile>) {
        val sub = serversSubmenu ?: return

        serverItems.forEach { sub.remove(it) }
        serverItems.clear()

        if (servers.isEmpty()) {
            if (noServersItem == null) {
                val placeholder = MenuItem("—")
                placeholder.setEnabled(false)
                sub.add(placeholder)
                noServersItem = placeholder
            }
            return
        }

        noServersItem?.let { sub.remove(it) }
        noServersItem = null

        servers.forEach { server ->
            val item = MenuItem(server.title ?: server.name) {
                onShowWindow?.invoke()
                onLaunchServer?.invoke(server)
            }
            sub.add(item)
            serverItems.add(item)
        }
    }

    fun setGameStatus(running: Boolean, serverName: String? = null) {
        statusItem?.setText(when {
            running && serverName != null -> "▶  $serverName"
            running -> strings?.statusRunning ?: "▶  Running"
            else    -> strings?.statusIdle ?: "●  Ready"
        })
    }

    fun shutdown() {
        runCatching { tray?.shutdown() }
        tray = null
        serverItems.clear()
        serversSubmenu = null
        statusItem = null
        noServersItem = null
        state = State.NOT_STARTED
    }
}
