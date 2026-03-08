package hivens.ui

import androidx.compose.runtime.*
import androidx.compose.ui.window.*
import hivens.config.AppConfig
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SessionData
import hivens.launcher.CrashReporter
import hivens.launcher.CredentialsManager
import hivens.launcher.ProfileManager
import hivens.launcher.di.appModule
import hivens.launcher.di.networkModule
import hivens.ui.components.UpdateManager
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.favicon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.logic.LauncherController
import hivens.ui.screens.ConsoleWindow
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.ThemeManager
import hivens.ui.utils.GameConsoleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinContext
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import javax.swing.SwingUtilities

// ─── DI ──────────────────────────────────────────────────────────────────────

val uiModule = module {
    singleOf(::LauncherController)
}

// ─── State ───────────────────────────────────────────────────────────────────

sealed class AppState {
    object Loading : AppState()
    object Unauthenticated : AppState()
    data class Authenticated(val session: SessionData) : AppState()
}

// ─── Navigation ──────────────────────────────────────────────────────────────

sealed class Screen {
    object Home        : Screen()
    object Profile     : Screen()
    object Settings    : Screen()
    object ThemePicker : Screen()
    object News        : Screen()
    data class ServerSettings(val server: hivens.core.api.model.ServerProfile) : Screen()
    data class ServerDetails (val server: hivens.core.api.model.ServerProfile) : Screen()
}

// ─── Entry Point ─────────────────────────────────────────────────────────────

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val logger = LoggerFactory.getLogger("CrashHandler")
        logger.error("Uncaught exception on thread '${thread.name}'", throwable)
        runCatching {
            val report     = CrashReporter.generate(throwable, thread)
            val reportFile = CrashReporter.saveToDisk(report)
            SwingUtilities.invokeLater { CrashReporter.showCrashDialog(report, reportFile) }
        }
    }

    startKoin { modules(networkModule, appModule, uiModule) }

    application {
        DisposableEffect(Unit) { onDispose { stopKoin() } }

        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)

        KoinContext {
            val settingsService: ISettingsService = koinInject()

            var isDarkTheme   by remember { mutableStateOf(true) }
            var currentLocale by remember {
                mutableStateOf(AppLocale.fromTag(settingsService.getSettings().locale))
            }

            LocaleProvider(locale = currentLocale) {
                val s = LocalStrings.current

                val dataDirectory: java.nio.file.Path = koinInject()
                val themeManager  = remember { ThemeManager(dataDirectory) }
                var customTheme   by remember { mutableStateOf(themeManager.loadTheme()) }

                val trayIcon = painterResource(Res.drawable.favicon)

                Tray(
                    icon    = trayIcon,
                    tooltip = "${AppConfig.APP_TITLE} v${AppConfig.CLIENT_VERSION.removePrefix("v")}",
                    menu    = {
                        Item(s.trayConsole, onClick = { GameConsoleService.show() })
                        Separator()
                        Item(s.trayExit, onClick = ::exitApplication)
                    }
                )

                if (GameConsoleService.shouldShowConsole) {
                    ConsoleWindow(isDarkTheme = isDarkTheme, onClose = { GameConsoleService.hide() })
                }

                Window(
                    onCloseRequest = ::exitApplication,
                    state     = windowState,
                    title     = AppConfig.APP_TITLE,
                    resizable = true,
                    icon      = trayIcon
                ) {
                    CelestiaTheme(useDarkTheme = isDarkTheme, customTheme = customTheme) {
                        AppRoot(
                            isDarkTheme          = isDarkTheme,
                            onToggleDarkTheme    = { isDarkTheme = !isDarkTheme },
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
}

// ─── App Root ─────────────────────────────────────────────────────────────────

@Composable
fun AppRoot(
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    customTheme: CustomTheme,
    onCustomThemeChanged: (CustomTheme) -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit
) {
    val credentialsManager: CredentialsManager = koinInject()
    val authService: IAuthService              = koinInject()
    val profileManager: ProfileManager         = koinInject()

    var appState      by remember { mutableStateOf<AppState>(AppState.Loading) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Auto-login runs in background while the 3-column UI is already visible
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val saved = credentialsManager.load()
            appState = if (saved?.cachedPassword != null) {
                try {
                    val server  = profileManager.lastServerId ?: AppConfig.DEFAULT_SERVER_ID
                    val session = authService.login(saved.playerName, saved.cachedPassword!!, server)
                    AppState.Authenticated(session)
                } catch (_: Exception) {
                    AppState.Unauthenticated
                }
            } else {
                AppState.Unauthenticated
            }
        }
    }

    AppLayout(
        appState             = appState,
        currentScreen        = currentScreen,
        onScreenChange       = { currentScreen = it },
        onLogin              = { session -> appState = AppState.Authenticated(session) },
        onLogout             = { credentialsManager.clear(); appState = AppState.Unauthenticated },
        isDarkTheme          = isDarkTheme,
        onToggleDarkTheme    = onToggleDarkTheme,
        customTheme          = customTheme,
        onCustomThemeChanged = onCustomThemeChanged,
        currentLocale        = currentLocale,
        onLocaleChanged      = onLocaleChanged
    )
}
