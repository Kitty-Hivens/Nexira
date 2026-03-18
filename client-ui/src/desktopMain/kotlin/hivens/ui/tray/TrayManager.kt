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

    data class Strings(
        val tooltip: String,
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
        if (tray != null) return

        try {
            SystemTray.DEBUG = false
            val t = SystemTray.get() ?: run {
                logger.warn("SystemTray not supported on this platform")
                return
            }
            tray = t
            t.setTooltip(strings.tooltip)
            t.setImage(iconStream)
            buildMenu(t.menu, strings)
            logger.info("TrayManager initialized ({})", t.javaClass.simpleName)
        } catch (t: Exception) {
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

    val isSupported: Boolean get() = tray != null

    fun shutdown() {
        runCatching { tray?.shutdown() }
        tray = null
        serverItems.clear()
        serversSubmenu = null
        statusItem = null
        noServersItem = null
    }
}
