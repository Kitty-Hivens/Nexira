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
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import hivens.config.Branding
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.HomeView
import hivens.core.data.SessionData
import hivens.core.data.UiStyle
import hivens.launcher.AutoSyncService
import hivens.launcher.bootstrap.AutoLoginCoordinator
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.CredentialsManager
import hivens.launcher.launch.LaunchState
import hivens.launcher.launch.LauncherController
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.ProfileManager
import hivens.ui.background.BackgroundManager
import hivens.ui.background.CustomBackground
import hivens.ui.components.UpdateManager
import hivens.ui.customization.CustomizationManager
import hivens.ui.customization.CustomizationSettings
import hivens.ui.customization.LocalCustomization
import hivens.ui.easter.AprilFools
import hivens.ui.easter.AprilFoolsLoader
import hivens.ui.easter.LocalAprilFools
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.screens.ConsoleWindow
import hivens.ui.screens.MigrationScreen
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.ThemeManager
import hivens.ui.tray.TrayManager
import hivens.ui.utils.GameConsoleService
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
import org.slf4j.LoggerFactory
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

// ─── State ───────────────────────────────────────────────────────────────────

sealed class AppState {
    object Loading : AppState()
    object Unauthenticated : AppState()
    data class Authenticated(val session: SessionData) : AppState()
}

// ─── Navigation ──────────────────────────────────────────────────────────────

sealed class Screen {
    object Home               : Screen()
    object Library            : Screen()
    object Browse             : Screen()
    object Profile            : Screen()
    object Settings           : Screen()
    object ThemePicker        : Screen()
    object About              : Screen()
    object BackgroundSettings : Screen()
    object CustomizationExtension : Screen()
    data class ServerSettings(val server: ServerProfile) : Screen()
    data class ServerDetails (val server: ServerProfile) : Screen()

    /**
     * Library card click target. Carries the PackInstance UUID; the
     * detail screen resolves it via [hivens.core.api.interfaces.IPackRepository]
     * so the Screen sealed class stays free of domain types and the
     * back-stack item stays small (a UUID string, not a PackInstance
     * graph).
     */
    data class PackDetail    (val instanceId: String) : Screen()

    /**
     * Browse-side detail target. Carries the mirror-side `pack_id`
     * (e.g. `"Industrial"`); the detail screen fetches manifest +
     * summary fresh via [hivens.launcher.smrt.SmrtPackClient]. Distinct
     * from [PackDetail] which resolves an installed [hivens.core.data.PackInstance]
     * via [hivens.core.api.interfaces.IPackRepository].
     */
    data class BrowsePackDetail(val packId: String) : Screen()
}

// ─── App Shell ───────────────────────────────────────────────────────────────

/**
 * Compose-side root. Holds the Window + tray bootstrap + raise-tick
 * .show watcher + migration / AppRoot branch. Receives the
 * pre-Compose [LauncherBootstrap.Result] from [Main.main]; everything
 * below this is pure Compose.
 *
 * Extension on [ApplicationScope] so `exitApplication()` works inside
 * the window close handler without re-wiring the receiver.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun ApplicationScope.AppShell(boot: LauncherBootstrap.Result) {
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
    var homeView      by remember { mutableStateOf(settings.homeView) }
    var uiStyle       by remember { mutableStateOf(settings.uiStyle) }

    val styleSpec = when (uiStyle) {
        UiStyle.Celestia -> CelestiaStyle
        UiStyle.Brut     -> BrutStyle
    }

    // Push style coupling into AprilFools so the chaos engine (a plain
    // singleton, not a Composable) and chaos components pick up the
    // active style without having to thread a CompositionLocal through
    // them. Triggered on every uiStyle change.
    LaunchedEffect(styleSpec) {
        AprilFools.styleAnimationMultiplier = styleSpec.animationMultiplier
        AprilFools.useFlatSurface           = styleSpec.cardSurface == hivens.ui.theme.CardSurface.Flat
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

        // Customization extension: persisted overrides for accent /
        // density / glass intensity / full color overrides. Provided
        // via [LocalCustomization] so CelestiaTheme + GlassCard can
        // read without prop-drilling.
        val customizationJson    = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
        val customizationManager = remember { CustomizationManager(dataDirectory, customizationJson) }
        var customization        by remember { mutableStateOf(customizationManager.load()) }

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
            // stuck with no reachable UI. The scenario is: user clicked
            // close during INITIALIZING window (the close handler uses
            // canBeReady, not isSupported, to avoid killing the launcher
            // mid-init). Without this restore the process keeps running
            // with no UI and the user has to kill it.
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

            val baseDensity   = androidx.compose.ui.platform.LocalDensity.current
            val scaledDensity = remember(baseDensity, customization.densityScale) {
                androidx.compose.ui.unit.Density(
                    baseDensity.density * customization.densityScale.coerceIn(0.5f, 2f),
                    baseDensity.fontScale,
                )
            }
            CompositionLocalProvider(
                LocalCustomization                       provides customization,
                androidx.compose.ui.platform.LocalDensity provides scaledDensity,
            ) {
            CelestiaTheme(
                useDarkTheme = isDarkTheme,
                customTheme  = customTheme,
                style        = styleSpec,
            ) {
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
                        },
                        homeView           = homeView,
                        onHomeViewChanged = { newView ->
                            homeView = newView
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(homeView = newView))
                        },
                        uiStyle           = uiStyle,
                        onUiStyleChanged  = { newStyle ->
                            uiStyle = newStyle
                            val current = settingsService.getSettings()
                            settingsService.saveSettings(current.copy(uiStyle = newStyle))
                        },
                        customization              = customization,
                        onCustomizationChanged     = { newCustomization ->
                            customization = newCustomization
                            customizationManager.save(newCustomization)
                        },
                    )
                    UpdateManager()
                }
            }
            } // end CompositionLocalProvider(LocalCustomization + LocalDensity)
        }
    }
    } // end CompositionLocalProvider(LocalAprilFools)
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
    onLocaleChanged: (AppLocale) -> Unit,
    homeView: HomeView,
    onHomeViewChanged: (HomeView) -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
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
    // Business logic lives in AutoLoginCoordinator; the Composable just
    // calls into it and maps the result into the local AppState machine.
    LaunchedEffect(Unit) {
        val session = withContext(Dispatchers.IO) {
            AutoLoginCoordinator.resolveSession(
                settings            = settingsService.getSettings(),
                saved               = credentialsManager.load(),
                lastServerId        = profileManager.lastServerId,
                authService         = authService,
                insecureAuthService = insecureAuthService,
                protocolConfig      = protocolConfig,
            )
        }
        appState = if (session != null) AppState.Authenticated(session) else AppState.Unauthenticated
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
                homeView = homeView,
                onHomeViewChanged = onHomeViewChanged,
                uiStyle = uiStyle,
                onUiStyleChanged = onUiStyleChanged,
                backgroundSettings = backgroundSettings,
                onBackgroundSettingsChanged = { newSettings ->
                    backgroundSettings = newSettings
                    backgroundManager.save(newSettings)
                    if (!newSettings.enabled || newSettings.imagePath != backgroundSettings.imagePath) {
                        System.gc()
                    }
                },
                customization              = customization,
                onCustomizationChanged     = onCustomizationChanged,
            )
        }
    }
}
