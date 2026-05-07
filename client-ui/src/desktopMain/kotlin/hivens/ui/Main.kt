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
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.CrashReporter
import hivens.launcher.CredentialsManager
import hivens.launcher.NetworkState
import hivens.launcher.ProfileManager
import hivens.launcher.di.appModule
import hivens.launcher.di.networkModule
import hivens.launcher.platform.DataDirMigration
import hivens.launcher.platform.PlatformPaths
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
import java.io.File
import java.io.FileOutputStream
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

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

@OptIn(ExperimentalResourceApi::class, DelicateCoroutinesApi::class)
fun main() {
    System.setProperty("jna.nosys", "true")
    System.setProperty("skiko.fps.limit", "60")
    // Match StartupWMClass in resources/aura-launcher.desktop so KDE/Hyprland/GNOME
    // can associate the running window with the .desktop entry (and thus pick up
    // the hicolor icon at the size the compositor actually wants — without this,
    // WM_CLASS is `java-MainKt` and the desktop falls back to the small embedded
    // window icon, blurring it in workspace overviews).
    System.setProperty("awt.appClassName", "AuraLauncher")

    val paths = PlatformPaths.system()
    DataDirMigration.run(paths)
    java.nio.file.Files.createDirectories(paths.dataDir)
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

    val lockFile = paths.dataDir.resolve(".lock").toFile()
    val lockChannel = FileOutputStream(lockFile).channel
    val lock = lockChannel.tryLock()

    if (lock == null) {
        paths.dataDir.resolve(".show").toFile().createNewFile()
        exitProcess(0)
    }

    startKoin { modules(networkModule, appModule, uiModule) }

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

        // If startInTray — keep hidden until tray is confirmed ready
        var isWindowVisible by remember { mutableStateOf(!settings.startInTray) }

        var isDarkTheme   by remember { mutableStateOf(settings.isDarkTheme) }
        var currentLocale by remember {
            mutableStateOf(AppLocale.fromTag(settings.locale))
        }

        val launchState by controller.state.collectAsState()


        LaunchedEffect(Unit) {
            val showFile = paths.dataDir.resolve(".show").toFile()
            while (true) {
                delay(500)
                if (showFile.exists()) {
                    showFile.delete()
                    isWindowVisible = true
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
            val themeManager  = remember { ThemeManager(dataDirectory) }
            var customTheme   by remember { mutableStateOf(themeManager.loadTheme()) }

            // Tray needs a 64-px glyph; the window chrome and KDE overview want the
            // detailed hi-res icon so they can downscale cleanly to whatever the
            // compositor demands.
            val trayIcon   = painterResource(Res.drawable.favicon)
            val windowIcon = painterResource(Res.drawable.icon)

            // ── Tray init on background thread ────────────────────────────
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val iconBytes = Res.readBytes("drawable/favicon.png")
                        TrayManager.init(
                            iconStream = iconBytes.inputStream(),
                            strings    = TrayManager.Strings(
                                tooltip       = "${Branding.TITLE} v${Branding.VERSION.removePrefix("v")}",
                                statusIdle    = s.trayStatusIdle,
                                statusRunning = s.trayStatusRunning,
                                show          = s.trayShow,
                                console       = s.trayConsole,
                                servers       = s.trayServers,
                                noServers     = s.trayNoServers,
                                exit          = s.trayExit
                            )
                        )
                    } catch (_: Exception) {
                        runCatching {
                            val iconBytes = Res.readBytes("drawable/icon.png")
                            TrayManager.init(
                                iconStream = iconBytes.inputStream(),
                                strings    = TrayManager.Strings(
                                    tooltip       = Branding.TITLE,
                                    statusIdle    = s.trayStatusIdle,
                                    statusRunning = s.trayStatusRunning,
                                    show          = s.trayShow,
                                    console       = s.trayConsole,
                                    servers       = s.trayServers,
                                    noServers     = s.trayNoServers,
                                    exit          = s.trayExit
                                )
                            )
                        }
                    }
                }

                // Tray failed to init — show window anyway so user isn't stuck
                if (settings.startInTray && !TrayManager.isSupported) {
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
                    GlobalScope.launch(Dispatchers.IO) {
                        val credentials = credentialsManager.load()
                        if (credentials?.cachedPassword != null) {
                            try {
                                val session = authService.login(
                                    credentials.playerName,
                                    credentials.cachedPassword!!,
                                    server.assetDir
                                )
                                controller.launch(session, server)
                                SwingUtilities.invokeLater { GameConsoleService.show() }
                            } catch (_: Exception) {
                                SwingUtilities.invokeLater { isWindowVisible = true }
                            }
                        } else {
                            SwingUtilities.invokeLater { isWindowVisible = true }
                        }
                    }
                }

                // ── Populate server list ───────────────────────────────────
                try {
                    val data = withContext(Dispatchers.IO) {
                        serverListService.fetchDashboardData().get()
                    }
                    TrayManager.updateServers(data.servers)
                } catch (_: Exception) { /* tray shows empty list */ }
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
                    } else if (TrayManager.isSupported) {
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
                CelestiaTheme(useDarkTheme = isDarkTheme, customTheme = customTheme) {
                    AppRoot(
                        onCloseApp = {
                            val gameRunning = launchState is LaunchState.GameRunning
                            if (gameRunning && TrayManager.isSupported) {
                                isWindowVisible = false
                            } else {
                                exitApplication()
                            }
                        },
                        onRealExit   = { exitApplication() },
                        onHideToTray = if (TrayManager.isSupported) {{ isWindowVisible = false }}
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
                    } catch (e: AuthException) {
                        if (e.isSslError) {
                            NetworkState.sslBypassEnabled = true
                            try {
                                val server  = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                                val session = insecureAuthService.login(saved.playerName, saved.cachedPassword!!, server)
                                AppState.Authenticated(session)
                            } catch (_: Exception) {
                                AppState.Unauthenticated
                            }
                        } else {
                            AppState.Unauthenticated
                        }
                    } catch (_: Exception) {
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
