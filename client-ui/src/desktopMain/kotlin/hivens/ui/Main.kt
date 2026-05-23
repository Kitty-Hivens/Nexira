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
import hivens.core.diag.ActionRing
import hivens.launcher.AutoSyncService
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.CredentialsManager
import hivens.launcher.network.NetworkState
import hivens.launcher.ProfileManager
import hivens.ui.background.BackgroundManager
import hivens.ui.background.CustomBackground
import hivens.ui.components.UpdateManager
import hivens.ui.easter.AprilFoolsLoader
import hivens.ui.easter.LocalAprilFools
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.screens.ConsoleWindow
import hivens.ui.screens.MigrationScreen
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.ThemeManager
import hivens.ui.tray.TrayManager
import hivens.ui.utils.GameConsoleService
import hivens.ui.identity.SkinManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

// ─── DI ──────────────────────────────────────────────────────────────────────

val uiModule = module {
    single { SkinManager(get(), get()) }
    single { GameConsoleService(get()) }
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

@OptIn(ExperimentalResourceApi::class)
fun main() {
    val boot = LauncherBootstrap.preBoot(listOf(uiModule))

    // Puppet mode: opt-in localhost HTTP control surface for automated
    // UI driving (see hivens.ui.puppet.PuppetServerLifecycle + Loader).
    // Two-layer gating: build-time SPI (RealPuppetServer ships only when
    // -PauraPuppetPort=N is on the Gradle command line) + runtime system
    // property (-Dnexira.puppet.port=N must be set to actually bind).
    // MUST run after Koin (LauncherBootstrap.preBoot) so PuppetRegistry-
    // using Composables can resolve their dependencies, and before
    // `application` so the server is listening when the first Composable
    // registers itself.
    hivens.ui.puppet.PuppetServerLoader.instance.startIfRequested()

    application {
        DisposableEffect(Unit) {
            onDispose {
                TrayManager.shutdown()
                hivens.ui.puppet.PuppetServerLoader.instance.stop()
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
        val gameConsole: GameConsoleService        = koinInject()
        // Shared process-lifetime scope (createdAtStart in appModule; canceled
        // by AppCoroutineScopeHook on JVM shutdown). Same instance backs
        // LauncherController.appScope and any other fire-and-forget work.
        val applicationScope: CoroutineScope        = koinInject()

        val settings = remember { settingsService.getSettings() }

        // Window starts visible. Tray is the dock-style fallback for
        // close-while-game-running, not a launcher hide-by-default
        // mode -- a start-in-tray toggle was tried and dropped; it
        // confused users (launcher invisible after first run) without
        // a clear use case.
        var isWindowVisible by remember { mutableStateOf(true) }

        var isDarkTheme   by remember { mutableStateOf(settings.isDarkTheme) }
        var currentLocale by remember {
            mutableStateOf(AppLocale.fromTag(settings.locale))
        }

        val launchState by controller.state.collectAsState()

        // Drains the controller's event channel into the console pane with
        // localized text. Lives at this level (not Dashboard) so events fire
        // regardless of which screen the user is currently viewing; the
        // collector is the seam that lets LauncherController stay free of
        // `client-ui` types (i18n, console). See `LaunchLogCollector` for the
        // event-to-string mapping.
        hivens.ui.logic.LaunchLogCollector(events = controller.events, gameConsole = gameConsole)


        // Bumped each time the .show signal fires; the Window content uses it
        // to invoke window.toFront() / requestFocus() so a duplicate-launch
        // attempt actually raises the existing instance, not just makes it
        // visible-but-buried-under-other-windows.
        var raiseTick by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            val showFile = boot.paths.dataDir.resolve(".show").toFile()
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

        // April Fools subsystem: provide the resolved lifecycle (Real or NoOp,
        // chosen by `AprilFoolsLoader`'s SPI scan) to every downstream
        // Composable. Wrapping at this level means tray/window close handlers
        // can capture `af` from the enclosing scope and still see the real
        // chaos flag in dev builds, while production binaries (no service
        // descriptor on the classpath) get NoOpAprilFools with zero chaos
        // overhead.
        val af = AprilFoolsLoader.instance

        CompositionLocalProvider(LocalAprilFools provides af) {
        LocaleProvider(locale = currentLocale) {
            val s = LocalStrings.current

            val dataDirectory: java.nio.file.Path = koinInject()
            val autoSyncService: AutoSyncService = koinInject()
            val themeManager  = remember { ThemeManager(dataDirectory) }
            var customTheme   by remember { mutableStateOf(themeManager.loadTheme()) }

            // Window chrome icon -- KDE overview / Hyprland switcher / macOS
            // dock want the detailed hi-res asset so they can be downscale
            // cleanly to whatever the compositor demands. The tray builds
            // its 64-px glyph from `drawable/favicon.png` separately via
            // `Res.readBytes(...)` so it doesn't need a Painter here.
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

                // Tray failed to init -- restore the window so the user isn't
                // stuck with no reachable UI. The scenario is:
                //   - the user clicked the close button during the
                //     INITIALIZING window (the close handler at the
                //     bottom of this file uses canBeReady, not
                //     isSupported, to avoid killing the launcher
                //     mid-init). Same outcome: window hidden, no tray
                //     either. Without this restore the process keeps
                //     running with no UI and the user has to kill it.
                if (!TrayManager.isSupported && !isWindowVisible) {
                    isWindowVisible = true
                }

                // ── Callbacks ─────────────────────────────────────────────
                TrayManager.onShowWindow = {
                    SwingUtilities.invokeLater { isWindowVisible = true }
                }

                TrayManager.onExit = {
                    SwingUtilities.invokeLater {
                        // Real impl pops the chaos close-dialog (during April Fools
                        // window); NoOp invokes onActualClose synchronously. The
                        // visibility flip is unconditional during chaos so the
                        // dialog isn't hidden behind a minimized window.
                        if (af.isActive()) isWindowVisible = true
                        af.requestCloseDialog { exitApplication() }
                    }
                }

                TrayManager.onShowConsole = {
                    SwingUtilities.invokeLater { gameConsole.show() }
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
                                SwingUtilities.invokeLater { gameConsole.show() }
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
                // applicationScope so it survives composition resets but does
                // get cancelled on JVM exit -- the alternative (GlobalScope)
                // leaks network/file handles past window close until the
                // process actually exits. The service itself is a singleton
                // and idempotent (no-ops on subsequent calls while already
                // running).
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
            if (gameConsole.shouldShowConsole) {
                ConsoleWindow(isDarkTheme = isDarkTheme, onClose = { gameConsole.hide() })
            }

            // ── Main window ────────────────────────────────────────────────
            Window(
                onCloseRequest = {
                    // af.requestCloseDialog dispatches: chaos active -> pop the
                    // torturous dialog; chaos inactive -> invoke the close path
                    // we'd have taken anyway (tray-hide if available, else exit).
                    af.requestCloseDialog {
                        if (TrayManager.canBeReady) {
                            // canBeReady (not isSupported) so we don't kill
                            // the launcher mid-init while the tray library
                            // is still settling D-Bus / SNI handshake. If
                            // it ultimately fails, the user can quit via
                            // tray (when it appears) or kill the process --
                            // strictly better than exiting on a close
                            // request the user clearly meant as "minimize".
                            isWindowVisible = false
                        } else {
                            exitApplication()
                        }
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
                        // Pulse it: enable -> toFront -> requestFocus -> disable.
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.requestFocus()
                        window.isAlwaysOnTop = false
                    }
                }

                CelestiaTheme(useDarkTheme = isDarkTheme, customTheme = customTheme) {
                    val migration = boot.pendingMigration
                    if (migration != null) {
                        // Migration is mandatory: the screen does not return
                        // to AppRoot on completion. The user clicks Quit and
                        // relaunches; the next process sees the .migrated
                        // marker and skips this branch. Local capture so
                        // the smart cast survives the nested MigrationScreen
                        // call -- boot.pendingMigration is a public property
                        // declared in client-launcher, and Kotlin's smart-cast
                        // doesn't extend across module boundaries.
                        MigrationScreen(
                            source = migration,
                            target = boot.paths.dataDir,
                            onQuit = { exitApplication() },
                        )
                    } else {
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
        } // end CompositionLocalProvider(LocalAprilFools)
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
    val insecureAuthService: IAuthService      = koinInject(named("insecure"))
    val protocolConfig: ServerProtocolConfig   = koinInject()
    // Smartycraft-routed Call.Factory for Coil's image fetcher. The
    // bypass / forceProxy / direct routing rule lives in Modules.kt
    // alongside the same rule for the Ktor HttpClientProvider; both
    // must agree or news / skin images would diverge from auth and
    // protocol traffic on the same host.
    val routingCallFactory: Call.Factory       = koinInject()
    val af = LocalAprilFools.current

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { routingCallFactory }))
            }
            .build()
    }

    var appState      by remember { mutableStateOf<AppState>(AppState.Loading) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // ── Background settings ───────────────────────────────────────────────
    val backgroundManager = remember { BackgroundManager(dataDirectory, json) }
    var backgroundSettings by remember { mutableStateOf(backgroundManager.load()) }

    // ── Auto-login with offline mode support ──────────────────────────────
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
                        // launcher startup -- which is what the cached
                        // accessToken is supposed to prevent. Trust the
                        // cache: promote `saved` straight to Authenticated.
                        // If the token is actually stale, the server will
                        // reject it at game launch and the user re-logs in
                        // from the credentials form -- same recovery path
                        // as a server-side logout. Fix for the "double
                        // login on every launch with 2FA" report.
                        ActionRing.record(
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
                            val until = Instant.now().plus(30, ChronoUnit.DAYS)
                            ActionRing.record("SSL bypass auto-granted on cached-credential auto-login (cert error) -- 30 days")
                            NetworkState.grantBypass(protocolConfig.sslBypassHost, until)
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

        af.WrapContent(
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
