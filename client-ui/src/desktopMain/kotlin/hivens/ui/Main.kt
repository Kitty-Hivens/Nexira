package hivens.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import hivens.client_ui.generated.resources.Res
import hivens.client_ui.generated.resources.favicon
import hivens.config.AppConfig
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SeasonTheme
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.ProfileManager
import hivens.launcher.di.appModule
import hivens.launcher.di.networkModule
import hivens.ui.components.GlassCard
import hivens.ui.components.SeasonalEffectsLayer
import hivens.ui.logic.LaunchUseCase
import hivens.ui.screens.*
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.SkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinContext
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import kotlin.math.cos
import kotlin.math.sin

val uiModule = module {
    singleOf(::LaunchUseCase)
}

sealed class AppState {
    data object Splash : AppState()                         // Экран инициализации
    data object Login : AppState()                          // Экран входа
    data class Shell(val session: SessionData) : AppState() // Основной интерфейс
}

sealed class ShellScreen {
    data object Home : ShellScreen()
    data object Profile : ShellScreen()
    data object GlobalSettings : ShellScreen()
    data object News : ShellScreen()
    data class ServerSettings(val server: ServerProfile) : ShellScreen()
}

fun main() {
    startKoin {
        modules(networkModule, appModule, uiModule)
    }

    application {
        val windowState = rememberWindowState(
            width = 1000.dp,
            height = 650.dp,
            position = WindowPosition(Alignment.Center)
        )
        var isDarkTheme by remember { mutableStateOf(true) }
        var isAppVisible by remember { mutableStateOf(true) }

        DisposableEffect(Unit) {
            onDispose { stopKoin() }
        }

        val trayIcon = painterResource(Res.drawable.favicon)

        Tray(
            icon = trayIcon,
            tooltip = "${AppConfig.APP_TITLE} v${AppConfig.CLIENT_VERSION}",
            onAction = { isAppVisible = !isAppVisible },
            menu = {
                Item("Показать/Скрыть", onClick = { isAppVisible = !isAppVisible })
                Item("Открыть консоль", onClick = { GameConsoleService.show() })
                Separator()
                Item("Выход", onClick = ::exitApplication)
            }
        )

        if (GameConsoleService.shouldShowConsole) {
            ConsoleWindow(onClose = { GameConsoleService.hide() })
        }

        KoinContext {
            Window(
                onCloseRequest = ::exitApplication,
                state = windowState,
                title = AppConfig.APP_TITLE,
                resizable = false,
                visible = isAppVisible,
                icon = trayIcon,
                undecorated = true,
                transparent = true
            ) {
                CelestiaTheme(useDarkTheme = isDarkTheme) {
                    AppContent(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { isDarkTheme = !isDarkTheme },
                        onCloseApp = ::exitApplication
                    )
                }
            }
        }
    }
}

@Composable
fun AppContent(isDarkTheme: Boolean, onToggleTheme: () -> Unit, onCloseApp: () -> Unit) {
    val credentialsManager: CredentialsManager = koinInject()
    val authService: IAuthService = koinInject()
    val profileManager: ProfileManager = koinInject()
    val settingsService: ISettingsService = koinInject()

    // Начинаем со SplahScreen
    var appState by remember { mutableStateOf<AppState>(AppState.Splash) }
    var seasonalTheme by remember { mutableStateOf(settingsService.getSettings().seasonalTheme) }

    // Логика инициализации (Запускается один раз в фоне)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Имитация загрузки, чтобы юзер увидел лого (убирает мелькание белого экрана)
            delay(800)

            val savedSession = credentialsManager.load()
            var nextState: AppState = AppState.Login

            // Пробуем авто-вход без блокировки UI
            if (savedSession?.cachedPassword != null) {
                try {
                    val lastServer = profileManager.lastServerId ?: AppConfig.DEFAULT_SERVER_ID
                    val session = authService.login(savedSession.playerName, savedSession.cachedPassword!!, lastServer)
                    nextState = AppState.Shell(session)
                } catch (e: Exception) {
                    LoggerFactory.getLogger("AppContent").warn("Auto-login failed: ${e.message}")
                }
            }
            // Переключаем состояние
            appState = nextState
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        CelestiaBackground(isDarkTheme = isDarkTheme, currentTheme = seasonalTheme)

        // Плавная смена экранов
        Crossfade(targetState = appState, animationSpec = tween(500)) { state ->
            when (state) {
                is AppState.Splash -> SplashScreen()
                is AppState.Login -> LoginScreen(onLoginSuccess = { session -> appState = AppState.Shell(session) })
                is AppState.Shell -> ShellUI(
                    initialSession = state.session,
                    onToggleTheme = onToggleTheme,
                    onLogout = { credentialsManager.clear(); appState = AppState.Login },
                    onCloseApp = onCloseApp,
                    onThemeChanged = { newTheme -> seasonalTheme = newTheme }
                )
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(Res.drawable.favicon),
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = CelestiaTheme.colors.primary
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = CelestiaTheme.colors.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                "Aura Launcher v${AppConfig.CLIENT_VERSION}",
                style = MaterialTheme.typography.caption,
                color = CelestiaTheme.colors.textSecondary
            )
        }
    }
}

@Composable
fun CelestiaBackground(isDarkTheme: Boolean, currentTheme: SeasonTheme) {
    val infiniteTransition = rememberInfiniteTransition()
    val t by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 6.28f,
        animationSpec = infiniteRepeatable(animation = tween(20000, easing = LinearEasing), repeatMode = RepeatMode.Restart)
    )
    val primaryColor = CelestiaTheme.colors.primary
    val successColor = CelestiaTheme.colors.success
    val bgAlpha = if (isDarkTheme) 0.15f else 0.05f
    val glowAlpha = if (isDarkTheme) 0.1f else 0.05f

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val x1 = width * 0.5f + cos(t) * width * 0.3f
        val y1 = height * 0.5f + sin(t) * height * 0.2f
        val x2 = width * 0.5f + cos(t + 3.14f) * width * 0.3f
        val y2 = height * 0.5f + sin(t * 0.8f) * height * 0.2f

        drawRect(brush = Brush.radialGradient(colors = listOf(primaryColor.copy(alpha = bgAlpha), Color.Transparent), center = Offset(x1, y1), radius = width * 0.6f))
        drawRect(brush = Brush.radialGradient(colors = listOf(successColor.copy(alpha = glowAlpha), Color.Transparent), center = Offset(x2, y2), radius = width * 0.5f))
    }

    SeasonalEffectsLayer(theme = currentTheme)
}

@Composable
fun ShellUI(
    initialSession: SessionData,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    onCloseApp: () -> Unit,
    onThemeChanged: (SeasonTheme) -> Unit
) {
    val skinRepository: SkinRepository = koinInject()
    var currentSession by remember { mutableStateOf(initialSession) }
    var currentScreen by remember { mutableStateOf<ShellScreen>(ShellScreen.Home) }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }
    var faceBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(currentSession.playerName) {
        faceBitmap = SkinManager.getSkinFront(currentSession.playerName)
    }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        GlassCard(modifier = Modifier.width(80.dp).fillMaxHeight(), shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxSize().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(CelestiaTheme.colors.surface).border(1.dp, CelestiaTheme.colors.primary.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.TopCenter) {
                    if (faceBitmap != null) {
                        Image(painter = BitmapPainter(faceBitmap!!), contentDescription = null, modifier = Modifier.size(48.dp).offset(y = 4.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    } else {
                        Text(currentSession.playerName.take(1).uppercase(), color = CelestiaTheme.colors.primary, modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(Modifier.height(16.dp))

                NavButton(Icons.Default.Home, currentScreen is ShellScreen.Home || currentScreen is ShellScreen.ServerSettings || currentScreen is ShellScreen.News) { currentScreen = ShellScreen.Home }
                NavButton(Icons.Default.Person, currentScreen is ShellScreen.Profile) { currentScreen = ShellScreen.Profile }
                NavButton(Icons.Default.Settings, currentScreen is ShellScreen.GlobalSettings) { currentScreen = ShellScreen.GlobalSettings }

                Spacer(Modifier.weight(1f))

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val isConsoleOpen = GameConsoleService.shouldShowConsole
                    IconButton(
                        onClick = {
                            if (isConsoleOpen) GameConsoleService.hide()
                            else GameConsoleService.show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = "Debug Console",
                            tint = if (isConsoleOpen) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout", tint = CelestiaTheme.colors.error.copy(alpha = 0.8f))
                    }
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        Box(Modifier.weight(1f).fillMaxHeight()) {
            Crossfade(targetState = currentScreen) { screen ->
                when (screen) {
                    is ShellScreen.Home -> DashboardScreen(
                        session = currentSession,
                        initialSelectedServer = selectedServer,
                        onServerSelected = { server -> selectedServer = server },
                        onSessionUpdated = { newSession -> currentSession = newSession },
                        onCloseApp = onCloseApp,
                        onOpenServerSettings = { server -> currentScreen = ShellScreen.ServerSettings(server) },
                        onOpenNews = { currentScreen = ShellScreen.News }
                    )
                    is ShellScreen.News -> NewsScreen(onBack = { currentScreen = ShellScreen.Home })
                    is ShellScreen.Profile -> ProfileScreen(currentSession, skinRepository)
                    is ShellScreen.GlobalSettings -> SettingsScreen(
                        isDarkTheme = true,
                        onToggleTheme = onToggleTheme,
                        onThemeChanged = onThemeChanged
                    )
                    is ShellScreen.ServerSettings -> ServerSettingsScreen(server = screen.server, onBack = { currentScreen = ShellScreen.Home })
                }
            }
        }
    }
}

@Composable
fun NavButton(icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = null, tint = if (isSelected) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
    }
}
