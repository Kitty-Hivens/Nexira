package hivens.ui.tray

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayEvent
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import hivens.core.api.model.ServerProfile
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * System tray manager backed by libtray (`dev.hivens:libtray`),
 * a Project-Panama-only replacement for dorkbox/SystemTray. Host renders
 * the menu via DBusMenu on Linux / Shell_NotifyIcon menu on Windows /
 * NSMenu on macOS — we publish the layout, the desktop draws it.
 *
 * Lifecycle:
 *   - `init(iconStream, strings, appName)` once from Main.kt after Koin
 *     is ready and the localised strings are resolved.
 *   - `setGameStatus` and `updateServers` may fire any time; they push a
 *     fresh menu/tooltip via libtray's setters.
 *   - `shutdown` on application exit. Idempotent.
 *
 * State machine still distinguishes NOT_STARTED / INITIALIZING / READY /
 * FAILED so the close-request handler in Main.kt can prefer "hide to
 * tray" over "exit application" while libtray is still bringing up the
 * D-Bus / Shell_NotifyIcon registration.
 */
object TrayManager {

    private val logger = LoggerFactory.getLogger("TrayManager")

    /**
     * Lifecycle state of the tray. Distinguishes "init has not even started"
     * from "init is running but hasn't completed yet" so the window's
     * close-request handler can prefer "minimize to tray" while libtray
     * is still bringing up the D-Bus / Shell_NotifyIcon side. Volatile
     * because [init] runs on Dispatchers.IO and the close-request
     * callbacks in Main.kt read state from the AWT thread.
     */
    enum class State { NOT_STARTED, INITIALIZING, READY, FAILED }

    @Volatile
    private var state: State = State.NOT_STARTED

    private var tray: Tray? = null
    private var strings: Strings? = null
    private var appName: String = "Aura Launcher"
    private var unsubscribe: (() -> Unit)? = null

    @Volatile private var servers: List<ServerProfile> = emptyList()
    @Volatile private var gameRunning: Boolean = false
    @Volatile private var gameServerName: String? = null

    /** True only when libtray has a live tray icon. */
    val isSupported: Boolean get() = state == State.READY

    /**
     * True when the tray either is ready or is still in the middle of
     * initializing. Use this for close-request handlers: if the tray
     * might still come up, prefer "hide to tray" over "exit application",
     * since the user's intent is "minimize" and we don't want to kill the
     * launcher because libtray is taking its time to register on D-Bus.
     */
    val canBeReady: Boolean get() = state == State.INITIALIZING || state == State.READY

    data class Strings(
        val statusIdle: String,
        val statusRunning: String,
        val show: String,
        val console: String,
        val servers: String,
        val noServers: String,
        val exit: String,
    )

    // ── Callbacks ─────────────────────────────────────────────────────────

    var onShowWindow: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onShowConsole: (() -> Unit)? = null
    var onLaunchServer: ((ServerProfile) -> Unit)? = null

    // ── Menu item ids — internal protocol with dispatchMenu ──────────────
    // Prefixed with `_` for items that shouldn't surface as launchable
    // events; "server:<assetDir>" for the per-server entries so the
    // dispatch can recover the asset id on click.

    private const val ID_SERVERS = "_servers"
    private const val ID_NOSERVERS = "_noservers"
    private const val ID_SHOW    = "show"
    private const val ID_CONSOLE = "console"
    private const val ID_EXIT    = "exit"
    private const val ID_SERVER_PREFIX = "server:"

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * @param iconStream Tray icon bytes (PNG). Read once into memory for libtray.
     * @param strings    Localised menu labels.
     * @param appName    The tray-host-visible title — what KDE/GNOME/Win11
     *                   tooltip resolves to when the user hovers. Stored as
     *                   the libtray Tray identifier for life of the icon.
     */
    fun init(iconStream: InputStream, strings: Strings, appName: String) {
        this.strings = strings
        this.appName = appName
        if (state != State.NOT_STARTED) return

        state = State.INITIALIZING
        try {
            val iconBytes = iconStream.readAllBytes()
            val builder = TrayBuilder(
                title = appName,
                iconBytes = iconBytes,
                tooltip = "$appName | ${strings.statusIdle}",
                menu = buildMenu(strings, servers, gameRunning, gameServerName),
            )
            val t = Tray.create(builder) ?: run {
                logger.warn("libtray Tray.create returned null — no tray host reachable on this session")
                state = State.FAILED
                return
            }
            tray = t
            unsubscribe = t.onEvent { event ->
                when (event) {
                    // Left click → restore window. Matches the previous
                    // dorkbox default and what every other tray-icon app
                    // does on every desktop.
                    is TrayEvent.Activated -> onShowWindow?.invoke()
                    is TrayEvent.MenuItemSelected -> dispatchMenu(event.id)
                    else -> Unit
                }
            }
            state = State.READY
            logger.info("TrayManager initialized via libtray (title='{}')", appName)
        } catch (t: Throwable) {
            state = State.FAILED
            logger.error("Failed to initialize TrayManager", t)
        }
    }

    private fun dispatchMenu(id: String) {
        when {
            id == ID_SHOW    -> onShowWindow?.invoke()
            id == ID_CONSOLE -> onShowConsole?.invoke()
            id == ID_EXIT    -> onExit?.invoke()
            id.startsWith(ID_SERVER_PREFIX) -> {
                val assetDir = id.removePrefix(ID_SERVER_PREFIX)
                val server = servers.firstOrNull { it.assetDir == assetDir } ?: return
                onShowWindow?.invoke()
                onLaunchServer?.invoke(server)
            }
            // _status / _noservers / _servers are non-clickable surfaces
            // (disabled items + the submenu parent itself); the host
            // shouldn't fire MenuItemSelected for them, but ignore
            // defensively in case it does.
        }
    }

    // ── State updates ─────────────────────────────────────────────────────

    fun updateServers(newServers: List<ServerProfile>) {
        servers = newServers
        rebuildMenu()
    }

    fun setGameStatus(running: Boolean, serverName: String? = null) {
        gameRunning = running
        gameServerName = serverName
        rebuildMenu()
        val s = strings
        val statusPart = when {
            running && serverName != null -> serverName
            running -> s?.statusRunning ?: "Running"
            else    -> s?.statusIdle ?: "Ready"
        }
        tray?.setTooltip("$appName | $statusPart")
    }

    private fun rebuildMenu() {
        val s = strings ?: return
        tray?.setMenu(buildMenu(s, servers, gameRunning, gameServerName))
    }

    private fun buildMenu(
        s: Strings,
        servers: List<ServerProfile>,
        @Suppress("UNUSED_PARAMETER") running: Boolean,
        @Suppress("UNUSED_PARAMETER") serverName: String?,
    ): TrayMenu {
        val items = mutableListOf<TrayMenuItem>()

        // Status used to live as the first (disabled) menu entry too, but
        // the same string is already in the SNI tooltip — the menu line
        // was pure duplication. Removed; running/serverName params are
        // kept on the signature so callers don't have to change shape.
        items += TrayMenuItem.Standard(id = ID_SHOW,    label = s.show)
        items += TrayMenuItem.Standard(id = ID_CONSOLE, label = s.console)
        items += TrayMenuItem.Separator

        // Servers submenu — non-empty fallback so the parent is never an
        // empty submenu (dbusmenu spec rejects that, libtray's
        // TrayMenuItem.Submenu init enforces it too).
        val serverItems: List<TrayMenuItem> = if (servers.isEmpty()) {
            listOf(TrayMenuItem.Standard(id = ID_NOSERVERS, label = s.noServers, enabled = false))
        } else {
            servers.map { srv ->
                TrayMenuItem.Standard(
                    id = "$ID_SERVER_PREFIX${srv.assetDir}",
                    label = srv.title ?: srv.name,
                )
            }
        }
        items += TrayMenuItem.Submenu(id = ID_SERVERS, label = s.servers, items = serverItems)
        items += TrayMenuItem.Separator
        items += TrayMenuItem.Standard(id = ID_EXIT, label = s.exit)

        return TrayMenu(items)
    }

    fun shutdown() {
        runCatching { unsubscribe?.invoke() }
        runCatching { tray?.close() }
        tray = null
        unsubscribe = null
        servers = emptyList()
        gameRunning = false
        gameServerName = null
        state = State.NOT_STARTED
    }
}
