package hivens.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build // [NEW] Иконка для консоли (гаечный ключ)
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.LauncherDI
import hivens.ui.components.GlassCard
import hivens.ui.screens.*
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.SkinManager
import kotlin.math.cos
import kotlin.math.sin

val di = LauncherDI()

sealed class AppState { // Вам не нравится код? Пожалуйста, сделайте форк :3. Умоляю вас
    data object Login : AppState()
    data class Shell(val session: SessionData) : AppState()
}

sealed class ShellScreen {
    data object Home : ShellScreen()
    data object Profile : ShellScreen()
    data object GlobalSettings : ShellScreen()
    data class ServerSettings(val server: ServerProfile) : ShellScreen()
}

fun main() = application {
    val windowState = rememberWindowState(width = 1000.dp, height = 650.dp)
    var isDarkTheme by remember { mutableStateOf(true) }

    // Окно консоли (появляется только если активно)
    if (GameConsoleService.shouldShowConsole) {
        ConsoleWindow(onClose = { GameConsoleService.hide() })
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Celestia", // TODO: А тут точно Селестия?
        resizable = false
    ) {
        CelestiaTheme(useDarkTheme = isDarkTheme) {
            var appState by remember { mutableStateOf<AppState>(AppState.Login) }

            LaunchedEffect(Unit) {
                val creds = di.credentialsManager.load()
                if (creds?.decryptedPassword != null) {
                    try {
                        val lastServer = di.profileManager.lastServerId ?: "Industrial"
                        val session = di.authService.login(creds.username, creds.decryptedPassword!!, lastServer)
                        appState = AppState.Shell(session)
                    } catch (e: Exception) {
                        println("Auto-login failed: ${e.message}")
                    }
                }
            }

            Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                CelestiaBackground(isDarkTheme = isDarkTheme)

                when (val state = appState) {
                    is AppState.Login -> LoginScreen(onLoginSuccess = { session -> appState = AppState.Shell(session) })
                    is AppState.Shell -> ShellUI(
                        initialSession = state.session,
                        onToggleTheme = { isDarkTheme = !isDarkTheme },
                        onLogout = { di.credentialsManager.clear(); appState = AppState.Login },
                        onCloseApp = ::exitApplication
                    )
                }
            }
        }
    }
}

@Composable
fun CelestiaBackground(isDarkTheme: Boolean) {
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
}

@Composable
fun ShellUI(initialSession: SessionData, onToggleTheme: () -> Unit, onLogout: () -> Unit, onCloseApp: () -> Unit) {
    var currentSession by remember { mutableStateOf(initialSession) }
    var currentScreen by remember { mutableStateOf<ShellScreen>(ShellScreen.Home) }
    var selectedServer by remember { mutableStateOf<ServerProfile?>(null) }
    var faceBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(currentSession.playerName) {
        faceBitmap = SkinManager.getSkinFront(currentSession.playerName)
    }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        // --- БОКОВАЯ ПАНЕЛЬ ---
        GlassCard(modifier = Modifier.width(80.dp).fillMaxHeight(), shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxSize().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Аватар
                Box(Modifier.size(48.dp).clip(CircleShape).background(CelestiaTheme.colors.surface).border(1.dp, CelestiaTheme.colors.primary.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.TopCenter) {
                    if (faceBitmap != null) {
                        Image(painter = BitmapPainter(faceBitmap!!), contentDescription = null, modifier = Modifier.size(48.dp).offset(y = 4.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    } else {
                        Text(currentSession.playerName.take(1).uppercase(), color = CelestiaTheme.colors.primary, modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Основная навигация
                NavButton(Icons.Default.Home, currentScreen is ShellScreen.Home || currentScreen is ShellScreen.ServerSettings) { currentScreen = ShellScreen.Home }
                NavButton(Icons.Default.Person, currentScreen is ShellScreen.Profile) { currentScreen = ShellScreen.Profile }
                NavButton(Icons.Default.Settings, currentScreen is ShellScreen.GlobalSettings) { currentScreen = ShellScreen.GlobalSettings }

                Spacer(Modifier.weight(1f))

                // Нижний блок: Консоль + Выход
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Стильная маленькая кнопка отладки
                    IconButton(onClick = { GameConsoleService.show() }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            // Гаечный ключ выглядит более "технически"
                            Icons.Default.Build,
                            contentDescription = "Debug Console",
                            tint = CelestiaTheme.colors.textSecondary.copy(alpha = 0.3f), // Очень тусклая, пока не наведешь
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Кнопка выхода
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout", tint = CelestiaTheme.colors.error.copy(alpha = 0.8f))
                    }
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        // --- КОНТЕНТ ---
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Crossfade(targetState = currentScreen) { screen ->
                when (screen) {
                    is ShellScreen.Home -> DashboardScreen(
                        session = currentSession,
                        initialSelectedServer = selectedServer,
                        onServerSelected = { server -> selectedServer = server },
                        onSessionUpdated = { newSession -> currentSession = newSession },
                        onCloseApp = onCloseApp,
                        onOpenServerSettings = { server -> currentScreen = ShellScreen.ServerSettings(server) }
                    )
                    is ShellScreen.Profile -> ProfileScreen(currentSession)
                    is ShellScreen.GlobalSettings -> SettingsScreen(isDarkTheme = true, onToggleTheme = onToggleTheme)
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
