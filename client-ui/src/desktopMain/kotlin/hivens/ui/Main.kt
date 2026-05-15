package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.*
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import hivens.config.Branding
import hivens.config.Protocol
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.AutoSyncService
import hivens.launcher.CrashReporter
import hivens.launcher.CredentialsManager
import hivens.launcher.NetworkState
import hivens.launcher.ProfileManager
import hivens.launcher.di.appModule
import hivens.launcher.di.networkModule
import hivens.launcher.platform.DataDirMigration
import hivens.launcher.platform.DataDirMover
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.platform.SingleInstance
import hivens.ui.background.BackgroundManager
import hivens.ui.background.CustomBackground
import hivens.ui.components.UpdateManager
import hivens.ui.easter.AprilFools
import hivens.ui.easter.AprilFoolsWrapper
import hivens.ui.easter.ChaosState
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.favicon
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.logic.LaunchState
import hivens.ui.logic.LauncherController
import hivens.ui.screens.ConsoleWindow
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.ThemeManager
import hivens.ui.tray.TrayManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.SkinManager
import java.awt.Toolkit
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

// ─── DI ──────────────────────────────────────────────────────────────────────

val uiModule = module {
    singleOf(::LauncherController)
    single { SkinManager(get(), get()) }
}

// ─── State ───────────────────────────────────────────────────────────────────

sealed class AppState {
    object Loading : AppState()
    object Unauthenticated : AppState()
    data class Authenticated(val session: SessionData) : AppState()
}

// ─── Navigation ──────────────────────────────────────────────────────────────

sealed class Screen {
    object Home               : Screen()
    object Profile            : Screen()
    object Settings           : Screen()
    object ThemePicker        : Screen()
    object About              : Screen()
    object BackgroundSettings : Screen()
    data class ServerSettings(val server: ServerProfile) : Screen()
    data class ServerDetails (val server: ServerProfile) : Screen()
}

// ─── Entry Point ─────────────────────────────────────────────────────────────

/**
 * Single startup line summarizing which AWT toolkit JBR/JDK picked and what
 * Linux display-server environment we're in. The Wayland-Native investigation
 * (docs/dev/wayland-investigation.md) needs every log we get back from a real
 * user to triangulate the toolkit-vs-session matrix; this line makes it
 * trivial to grep across `launcher.log` files attached to bundles.
 *
 * Always-on (not gated by AURA_WAYLAND_TRIAL) — the diagnostic value applies
 * to every Linux user, not just trial participants.
 */
private fun logToolkitAndSession() {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return
    val toolkit = runCatching { java.awt.Toolkit.getDefaultToolkit().javaClass.name }
        .getOrElse { "<unavailable: ${it.javaClass.simpleName}>" }
    fun env(k: String) = System.getenv(k) ?: "<unset>"
    LoggerFactory.getLogger("Main").info(
        "Display: toolkit={} XDG_SESSION_TYPE={} XDG_CURRENT_DESKTOP={} WAYLAND_DISPLAY={} DISPLAY={}",
        toolkit, env("XDG_SESSION_TYPE"), env("XDG_CURRENT_DESKTOP"),
        env("WAYLAND_DISPLAY"), env("DISPLAY"),
    )
}

/**
 * Force the X11 toolkit's app class name so the WM_CLASS hint on every window
 * we create matches `StartupWMClass=` in the .desktop entry.
 *
 * Stock OpenJDK derives WM_CLASS from the launcher binary's argv[0] and exposes
 * no public knob to override it; JBR exposes `-Dawt.appClassName` but only that
 * one vendor honors it. Reflection into the package-private static field works
 * across both — provided we run before any window is shown (XWindow.setWMClass
 * snapshots the value at construction time) and the JVM was launched with
 * `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`.
 *
 * No-op on macOS/Windows. Failures are logged but never fatal — a wrong icon
 * is annoying, not crash-worthy.
 */
private fun setLinuxXToolkitAppClassName(name: String) {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return
    runCatching {
        // Triggers XToolkit class load + initial awtAppClassName assignment.
        Toolkit.getDefaultToolkit()
        val cls = Class.forName("sun.awt.X11.XToolkit")
        val field = cls.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, name)
    }.onFailure {
        LoggerFactory.getLogger("Main").warn(
            "Could not override XToolkit.awtAppClassName ({}); " +
            "compositors may show a generic icon. Cause: {}",
            name, it.toString()
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
fun main() {
    // Resolve logs dir BEFORE any LoggerFactory.getLogger() call so
    // logback.xml (which reads `${aura.logs.dir}` for its rolling-file
    // appenders) sees the platform-correct path on its very first init.
    // PlatformPaths.system() is pure computation — no logger init —
    // safe to call before the property is set. DataDirMover and
    // BootstrapConf both have lazy log fields specifically so this
    // ordering works without their applyPending() / read() touching
    // logback first; see those files for the long-form rationale.
    val paths = PlatformPaths.system()
    System.setProperty("aura.logs.dir", paths.logsDir.toString())

    // NOW safe to apply any pending data-dir move scheduled from the
    // Settings UI. If user clicked "Move data directory" → picker →
    // restart, this is where the relocation actually happens. Operation
    // is idempotent; safe to call on every startup. The first log line
    // it produces (only in the actual-move case, no-op otherwise) lands
    // in `paths.logsDir/launcher.log`, not `./logs/launcher.log`.
    //
    // Edge case: if applyPending DOES move the data dir, paths.logsDir
    // points at the old location and logback opens the file there —
    // log entries about the move itself stream to the old path right
    // up until the source dir is deleted. Next startup uses the new
    // path correctly. The user opted into this two-restart flow when
    // they clicked Move, so the one-time misdirect is acceptable.
    DataDirMover.applyPending()

    // Pulse: tag every log line in this process with a stable 8-char sessionId
    // so a multi-launch user dump can be sliced per process invocation
    // (`grep sessionId=abc12345 *.log`). System property (not MDC) because
    // MDC is thread-local and we want this on every line from every thread —
    // the logback pattern reads the property via `${aura.sessionId}`.
    val sessionId = UUID.randomUUID().toString().take(8)
    System.setProperty("aura.sessionId", sessionId)

    // Beacon: the very first entry in the action ring — handy when reading a
    // bundle to confirm what process / version / OS produced it.
    hivens.core.diag.ActionRing.record(
        "Launcher started (v${Branding.VERSION}, sessionId=$sessionId, os=${System.getProperty("os.name")})"
    )

    // Vault #2: wire SSL-bypass persistence. Expired entries from prior
    // sessions are dropped during load — a 30-day grant from a month ago
    // doesn't silently re-arm itself. Called before Koin / HttpClientProvider
    // bootstrap so the very first network request sees the correct bypass
    // state. (Calling later would race: HttpClientProvider's selector
    // reads `NetworkState.bypassFor(...)` and could see an empty set if
    // initialize hadn't run yet.)
    NetworkState.initialize(paths.dataDir.resolve("ssl-bypasses.json"))

    System.setProperty("jna.nosys", "true")
    System.setProperty("skiko.fps.limit", "60")
    // X11 WM_CLASS = "AuraLauncher". -Dawt.appClassName covers JBR; for stock
    // OpenJDK we reflect into sun.awt.X11.XToolkit.awtAppClassName before the
    // first window is created. See jvmArgs in client-ui/build.gradle.kts.
    setLinuxXToolkitAppClassName(Branding.WM_CLASS)

    // Capture toolkit + session-type as soon as the toolkit has been triggered
    // by setLinuxXToolkitAppClassName above. One INFO line per launch — gives
    // every user-attached `launcher.log` enough context to slot into the
    // Wayland-Native investigation matrix.
    logToolkitAndSession()

    Files.createDirectories(paths.dataDir)
    CrashReporter.paths = paths

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val logger = LoggerFactory.getLogger("CrashHandler")
        logger.error("Uncaught exception on thread '${thread.name}'", throwable)
        runCatching {
            val report     = CrashReporter.generate(throwable, thread)
            val reportFile = CrashReporter.saveToDisk(report)
            SwingUtilities.invokeLater { CrashReporter.showCrashDialog(report, reportFile) }
        }
    }

    // Single-instance lock acquired BEFORE migration. Two launchers started
    // close together would otherwise both reach DataDirMigration.run() and
    // race on REPLACE_EXISTING file copies. DataDirMigration's emptiness
    // check is taught to ignore .lock / .show / .migrated so its first-run
    // trigger still fires.
    if (!SingleInstance.acquire(paths.dataDir)) exitProcess(0)

    DataDirMigration.run(paths)

    startKoin { modules(networkModule, appModule, uiModule) }

    // Process-lifetime coroutine scope for fire-and-forget background work
    // (tray-launch flow, AutoSync). SupervisorJob so a single failed child
    // doesn't take down the rest. Shutdown hook cancels the scope on JVM
    // exit so in-flight network sockets / file handles get released instead
    // of being orphaned the way they were under GlobalScope (#191).
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    Runtime.getRuntime().addShutdownHook(Thread { applicationScope.cancel() })

    // Conduit Phase 2: restore persisted force-proxy preference into the
    // in-memory NetworkState so ChannelRouter sees it on the very first
    // network call. MUST run after startKoin — the previous version called
    // KoinJavaComponent.get() before bootstrap and silently failed via
    // runCatching, leaving the toggle effectively non-persistent.
    runCatching {
        val persistedSettings = org.koin.java.KoinJavaComponent.get<ISettingsService>(
            ISettingsService::class.java
        ).getSettings()
        NetworkState.setForceProxyMode(persistedSettings.forceProxyMode)
    }.onFailure {
        LoggerFactory.getLogger("Main")
            .warn("Failed to restore persisted forceProxyMode at startup", it)
    }

    application {
        DisposableEffect(Unit) {
            onDispose {
                TrayManager.shutdown()
                stopKoin()
            }
        }

        val windowState      = rememberWindowState(placement = WindowPlacement.Maximized)
        val settingsService: ISettingsService      = koinInject()
        val serverListService: IServerListService  = koinInject()
        val controller: LauncherController         = koinInject()
        val credentialsManager: CredentialsManager = koinInject()
        val authService: IAuthService              = koinInject()
        val profileManager: ProfileManager         = koinInject()

        val settings = remember { settingsService.getSettings() }

        // Window starts visible — `startInTray` was retired in 2.2.14:
        // it confused users (launcher invisible after first run) and
        // had no clear use case. Tray is the dock-style fallback for
        // close-while-game-running, not a launcher hide-by-default mode.
        var isWindowVisible by remember { mutableStateOf(true) }

        var isDarkTheme   by remember { mutableStateOf(settings.isDarkTheme) }
        var currentLocale by remember {
            mutableStateOf(AppLocale.fromTag(settings.locale))
        }

        val launchState by controller.state.collectAsState()


        // Bumped each time the .show signal fires; the Window content uses it
        // to invoke window.toFront() / requestFocus() so a duplicate-launch
        // attempt actually raises the existing instance, not just makes it
        // visible-but-buried-under-other-windows.
        var raiseTick by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            val showFile = paths.dataDir.resolve(".show").toFile()
            while (true) {
                delay(500.milliseconds)
                if (showFile.exists()) {
                    showFile.delete()
                    // Un-minimize: setting visible=true alone leaves a taskbar-minimized window minimized.
                    if (windowState.isMinimized) windowState.isMinimized = false
                    isWindowVisible = true
                    raiseTick++
                }
            }
        }

        LaunchedEffect(launchState) {
            val serverName = profileManager.lastServerId
            when (launchState) {
                is LaunchState.GameRunning -> TrayManager.setGameStatus(true, serverName)
                is LaunchState.Error -> {
                    TrayManager.setGameStatus(false)
                    if (!isWindowVisible) {
                        SwingUtilities.invokeLater { isWindowVisible = true }
                    }
                }
                else -> TrayManager.setGameStatus(false)
            }
        }

        LocaleProvider(locale = currentLocale) {
            val s = LocalStrings.current

            val dataDirectory: java.nio.file.Path = koinInject()
            val autoSyncService: AutoSyncService = koinInject()
            val themeManager  = remember { ThemeManager(dataDirectory) }
            var customTheme   by remember { mutableStateOf(themeManager.loadTheme()) }

            // Tray needs a 64-px glyph; the window chrome and KDE overview want the
            // detailed hi-res icon so they can be downscale cleanly to whatever the
            // compositor demands.
            val trayIcon   = painterResource(Res.drawable.favicon) // TODO: not used
            val windowIcon = painterResource(Res.drawable.icon)

            // ── Tray init on background thread ────────────────────────────
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val iconBytes = Res.readBytes("drawable/favicon.png")
                        TrayManager.init(
                            iconStream = iconBytes.inputStream(),
                            strings    = TrayManager.Strings(
                                statusIdle    = s.trayStatusIdle,
                                statusRunning = s.trayStatusRunning,
                                show          = s.trayShow,
                                console       = s.trayConsole,
                                servers       = s.trayServers,
                                noServers     = s.trayNoServers,
                                exit          = s.trayExit
                            ),
                            appName    = Branding.TITLE
                        )
                    } catch (_: Exception) {
                        runCatching {
                            val iconBytes = Res.readBytes("drawable/icon.png")
                            TrayManager.init(
                                iconStream = iconBytes.inputStream(),
                                strings    = TrayManager.Strings(
                                    statusIdle    = s.trayStatusIdle,
                                    statusRunning = s.trayStatusRunning,
                                    show          = s.trayShow,
                                    console       = s.trayConsole,
                                    servers       = s.trayServers,
                                    noServers     = s.trayNoServers,
                                    exit          = s.trayExit
                                ),
                                appName    = Branding.TITLE
                            )
                        }
                    }
                }

                // Tray failed to init — restore the window so the user isn't
                // stuck with no reachable UI. The scenario is:
                //   - the user clicked the close button during the
                //     INITIALIZING window (the close handler at the bottom
                //     of this file uses canBeReady, not isSupported,
                //      not isSupported, to avoid killing the launcher
                //      mid-init). Same outcome — window hidden, no tray
                //      either. Without this restore the process keeps
                //      running with no UI and the user has to kill it.
                //   (Codex P1 from PR #131 — the canBeReady-during-INIT
                //   path needs this failure-path unhide.)
                if (!TrayManager.isSupported && !isWindowVisible) {
                    isWindowVisible = true
                }

                // ── Callbacks ─────────────────────────────────────────────
                TrayManager.onShowWindow = {
                    SwingUtilities.invokeLater { isWindowVisible = true }
                }

                TrayManager.onExit = {
                    SwingUtilities.invokeLater {
                        if (AprilFools.isActive()) {
                            // Show the torturous close dialog instead of quitting immediately
                            ChaosState.showCloseDialog = true
                            // Also make the window visible so the dialog is actually seen
                            isWindowVisible = true
                        } else {
                            exitApplication()
                        }
                    }
                }

                TrayManager.onShowConsole = {
                    SwingUtilities.invokeLater { GameConsoleService.show() }
                }

                TrayManager.onLaunchServer = { server ->
                    applicationScope.launch {
                        val credentials = credentialsManager.load()
                        if (credentials?.cachedPassword != null) {
                            try {
                                val session = try {
                                    authService.login(
                                        credentials.playerName,
                                        credentials.cachedPassword!!,
                                        server.assetDir,
                                    )
                                } catch (_: TwoFactorRequiredException) {
                                    // Tray-launched 2FA accounts: same trust-the-cache
                                    // policy as the auto-login path. controller.launch
                                    // augments the session with a cached manifest if
                                    // needed (and reports cleanly when the cache is
                                    // empty, which is its job).
                                    credentials.copy(serverId = server.assetDir)
                                }
                                controller.launch(session, server)
                                SwingUtilities.invokeLater { GameConsoleService.show() }
                            } catch (e: Exception) {
                                LoggerFactory.getLogger("Main").warn(
                                    "Tray-launched login failed for ${server.assetDir}", e
                                )
                                SwingUtilities.invokeLater { isWindowVisible = true }
                            }
                        } else {
                            SwingUtilities.invokeLater { isWindowVisible = true }
                        }
                    }
                }

                // ── Populate server list ───────────────────────────────────
                val dashboardServers = try {
                    val data = withContext(Dispatchers.IO) {
                        serverListService.fetchDashboardData().get()
                    }
                    TrayManager.updateServers(data.servers)
                    data.servers
                } catch (_: Exception) {
                    /* tray shows empty list */
                    emptyList()
                }

                // ── Auto-sync (experimental, opt-in) ──────────────────────
                // Fire-and-forget background sync of every installed pack.
                // Gated by experimentalFeaturesEnabled master + autoSyncAllPacks
                // child to match the rest of the experimental opt-ins. Runs on
                // applicationScope so it survives composition resets but DOES
                // get cancelled on JVM exit — under the prior GlobalScope a
                // user closing the launcher mid-sync left network/file handles
                // open until the process truly died (#191). The service itself
                // is a singleton and idempotent (will no-op on subsequent calls
                // if already running — TODO: enforce via in-flight flag once we
                // add UI re-trigger).
                if (settings.experimentalFeaturesEnabled
                    && settings.autoSyncAllPacks
                    && dashboardServers.isNotEmpty()
                ) {
                    applicationScope.launch {
                        autoSyncService.syncAll(dashboardServers)
                    }
                }
            }

            // ── Console window ─────────────────────────────────────────────
            if (GameConsoleService.shouldShowConsole) {
                ConsoleWindow(isDarkTheme = isDarkTheme, onClose = { GameConsoleService.hide() })
            }

            // ── Main window ────────────────────────────────────────────────
            Window(
                onCloseRequest = {
                    if (AprilFools.isActive()) {
                        ChaosState.showCloseDialog = true
                    } else if (TrayManager.canBeReady) {
                        // canBeReady (not isSupported) so we don't kill the
                        // launcher mid-init when dorkbox's GTK probe is
                        // taking its time. If it ultimately fails, the user
                        // can quit via tray (when it appears) or kill the
                        // process — strictly better than exiting on a close
                        // request the user clearly meant as "minimize".
                        isWindowVisible = false
                    } else {
                        exitApplication()
                    }
                },
                state     = windowState,
                visible   = isWindowVisible,
                title     = Branding.TITLE,
                resizable = true,
                icon      = windowIcon
            ) {
                // Pulled-forward: triggered by the .show watcher above when a
                // second instance fires its signal. Skip on raiseTick == 0 so
                // the first composition doesn't steal focus from whatever the
                // user was doing when the launcher started.
                LaunchedEffect(raiseTick) {
                    if (raiseTick == 0) return@LaunchedEffect
                    SwingUtilities.invokeLater {
                        // The isAlwaysOnTop trick is the only cross-WM way to
                        // force a raise on X11 (KDE / Hyprland / GNOME all
                        // ignore plain toFront() to discourage focus-stealing).
                        // Pulse it: enable → toFront → requestFocus → disable.
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.requestFocus()
                        window.isAlwaysOnTop = false
                    }
                }

                CelestiaTheme(useDarkTheme = isDarkTheme, customTheme = customTheme) {
                    AppRoot(
                        onCloseApp = {
                            val gameRunning = launchState is LaunchState.GameRunning
                            if (gameRunning && TrayManager.canBeReady) {
                                // Same canBeReady reasoning as the Window
                                // onCloseRequest: don't pull the rug from
                                // under a running game just because tray
                                // init is still mid-flight.
                                isWindowVisible = false
                            } else {
                                exitApplication()
                            }
                        },
                        onRealExit   = { exitApplication() },
                        onHideToTray = if (TrayManager.canBeReady) {{ isWindowVisible = false }}
                        else null,
                        isDarkTheme          = isDarkTheme,
                        onToggleDarkTheme    = {
                            isDarkTheme = !isDarkTheme
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(isDarkTheme = isDarkTheme))
                        },
                        customTheme          = customTheme,
                        onCustomThemeChanged = { newTheme ->
                            customTheme = newTheme
                            themeManager.saveTheme(newTheme)
                        },
                        currentLocale   = currentLocale,
                        onLocaleChanged = { newLocale ->
                            currentLocale = newLocale
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(locale = newLocale.tag))
                        }
                    )
                    UpdateManager()
                }
            }
        }
    }
}

// ─── App Root ─────────────────────────────────────────────────────────────────

@Composable
fun AppRoot(
    onCloseApp: () -> Unit,
    isDarkTheme: Boolean,
    onRealExit: () -> Unit,
    onHideToTray: (() -> Unit)?,
    onToggleDarkTheme: () -> Unit,
    customTheme: CustomTheme,
    onCustomThemeChanged: (CustomTheme) -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit
) {
    val credentialsManager: CredentialsManager = koinInject()
    val authService: IAuthService              = koinInject()
    val profileManager: ProfileManager         = koinInject()
    val settingsService: ISettingsService      = koinInject()
    val dataDirectory: java.nio.file.Path      = koinInject()
    val json: Json                             = koinInject()
    val httpClient: OkHttpClient               = koinInject()
    val insecureAuthService: IAuthService      = koinInject(named("insecure"))

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { httpClient }))
            }
            .build()
    }

    var appState      by remember { mutableStateOf<AppState>(AppState.Loading) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // ── Background settings ───────────────────────────────────────────────
    val backgroundManager = remember { BackgroundManager(dataDirectory, json) }
    var backgroundSettings by remember { mutableStateOf(backgroundManager.load()) }

    // ── Auto-login with offline mode support (#63) ────────────────────────
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val settings = settingsService.getSettings()
            val saved    = credentialsManager.load()

            appState = when {
                settings.isOfflineMode && saved != null -> {
                    val offlineSession = SessionData(
                        playerName     = saved.playerName,
                        uuid           = saved.uuid.ifBlank { "offline-${saved.playerName}" },
                        uid            = saved.uid,
                        accessToken    = "offline",
                        cachedPassword = saved.cachedPassword,
                        status         = null,
                        serverId       = profileManager.lastServerId
                    )
                    AppState.Authenticated(offlineSession)
                }
                settings.isOfflineMode -> AppState.Unauthenticated
                saved?.cachedPassword != null -> {
                    try {
                        val server  = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                        val session = authService.login(saved.playerName, saved.cachedPassword!!, server)
                        AppState.Authenticated(session)
                    } catch (e: TwoFactorRequiredException) {
                        // 2FA accounts already paid the 2FA cost when they
                        // got the cached accessToken. Re-validating with
                        // login() just re-triggers the gate on every
                        // launcher startup — which is what the cached
                        // accessToken is supposed to prevent. Trust the
                        // cache: promote `saved` straight to Authenticated.
                        // If the token is actually stale, the server will
                        // reject it at game launch and the user re-logs in
                        // from the credentials form — same recovery path
                        // as a server-side logout. Fix for the "double
                        // login on every launch with 2FA" report.
                        hivens.core.diag.ActionRing.record(
                            "Auto-login: 2FA account, trusting cached accessToken (uid=${e.uid?.take(8) ?: "<missing>"})"
                        )
                        AppState.Authenticated(
                            saved.copy(serverId = profileManager.lastServerId),
                        )
                    } catch (e: AuthException) {
                        if (e.isSslError) {
                            // Auto-grant on cached-credential cert error gets the same
                            // 30-day expiry as user-initiated accept (RightPanel). The
                            // user accepted the SSL bypass implicitly by saving credentials
                            // through a prior cert outage; we extend that consent until
                            // the cert issue resolves or 30 days, whichever comes first.
                            val until = java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS)
                            hivens.core.diag.ActionRing.record("SSL bypass auto-granted on cached-credential auto-login (cert error) — 30 days")
                            NetworkState.grantBypass(hivens.config.Network.SSL_BYPASS_HOST, until)
                            try {
                                val server  = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                                val session = insecureAuthService.login(saved.playerName, saved.cachedPassword!!, server)
                                AppState.Authenticated(session)
                            } catch (e2: Exception) {
                                LoggerFactory.getLogger("Main").warn(
                                    "Auto-login with cached credentials failed after SSL bypass", e2
                                )
                                AppState.Unauthenticated
                            }
                        } else {
                            LoggerFactory.getLogger("Main").warn(
                                "Cached-credential auto-login failed (non-SSL)", e
                            )
                            AppState.Unauthenticated
                        }
                    } catch (e: Exception) {
                        LoggerFactory.getLogger("Main").warn(
                            "Cached-credential auto-login failed with non-Auth exception", e
                        )
                        AppState.Unauthenticated
                    }
                }
                else -> AppState.Unauthenticated
            }
        }
    }

    // ── Render: background behind layout ──────────────────────────────────
    val mousePos    = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val mousePxPos  = remember { mutableStateOf(Offset.Zero) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { windowSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null && windowSize.width > 0 && windowSize.height > 0) {
                                mousePxPos.value = pos
                                mousePos.value   = Offset(pos.x / windowSize.width, pos.y / windowSize.height)
                            }
                        }
                    }
                }
            }
    ) {
        CustomBackground(settings = backgroundSettings, mousePosProvider = { mousePos.value })
        /*
        SkiaDebugOverlay(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        )
         */

        AprilFoolsWrapper(
            pixelCursorState = mousePxPos,
            windowSize       = windowSize,
            onRealClose      = onRealExit,
            onHideTray       = onHideToTray,
        ) {
            AppLayout(
                appState = appState,
                onCloseApp = onCloseApp,
                currentScreen = currentScreen,
                onScreenChange = { currentScreen = it },
                onLogin = { session -> appState = AppState.Authenticated(session) },
                onLogout = { credentialsManager.clear(); appState = AppState.Unauthenticated },
                isDarkTheme = isDarkTheme,
                onToggleDarkTheme = onToggleDarkTheme,
                customTheme = customTheme,
                onCustomThemeChanged = onCustomThemeChanged,
                currentLocale = currentLocale,
                onLocaleChanged = onLocaleChanged,
                backgroundSettings = backgroundSettings,
                onBackgroundSettingsChanged = { newSettings ->
                    backgroundSettings = newSettings
                    backgroundManager.save(newSettings)
                    if (!newSettings.enabled || newSettings.imagePath != backgroundSettings.imagePath) {
                        System.gc()
                    }
                }
            )
        }
    }
}
