package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.ProfileManager
import hivens.ui.components.GlassCard
import hivens.ui.components.LaunchControlPanel
import hivens.ui.components.SquareServerCard
import hivens.ui.logic.LaunchState
import hivens.ui.logic.LauncherController
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DashboardScreen(
    session: SessionData,
    initialSelectedServer: ServerProfile?,
    onServerSelected: (ServerProfile) -> Unit,
    onSessionUpdated: (SessionData) -> Unit,
    onCloseApp: () -> Unit,
    onOpenServerSettings: (ServerProfile) -> Unit,
    onOpenNews: () -> Unit,
    onOpenDetails: (ServerProfile) -> Unit
) {
    val serverListService: IServerListService = koinInject()
    val settingsService: ISettingsService = koinInject()
    val profileManager: ProfileManager = koinInject()
    val controller: LauncherController = koinInject()

    val launchState by controller.state.collectAsState()

    var servers by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServerState by remember { mutableStateOf(initialSelectedServer) }

    var favoriteTrigger by remember { mutableStateOf(0) }
    val favorites = remember(favoriteTrigger) { profileManager.favoriteServers }

    // ЛОГИКА: Закрытие приложения после запуска (Fix "Parameter onCloseApp is never used")
    LaunchedEffect(launchState) {
        if (launchState is LaunchState.GameRunning) {
            val settings = settingsService.getSettings()
            if (settings.closeAfterStart) {
                onCloseApp()
            }
        }
    }

    // ЛОГИКА: Загрузка серверов
    LaunchedEffect(Unit) {
        if (servers.isEmpty()) {
            try {
                val data = withContext(Dispatchers.IO) {
                    serverListService.fetchDashboardData().get()
                }
                servers = data.servers

                if (selectedServerState == null) {
                    val lastId = profileManager.lastServerId
                    val default = servers.find { it.assetDir == lastId } ?: servers.firstOrNull()
                    if (default != null) {
                        selectedServerState = default
                        onServerSelected(default)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Синхронизация выбора из Main
    LaunchedEffect(initialSelectedServer) {
        if (initialSelectedServer != null) {
            selectedServerState = initialSelectedServer
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // --- ХЕДЕР: Приветствие и Кнопка Новостей ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ДОБРО ПОЖАЛОВАТЬ, ${session.playerName.uppercase()}",
                style = MaterialTheme.typography.h5,
                color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.7f)
            )

            // Кнопка НОВОСТИ
            IconButton(
                onClick = onOpenNews,
                // Блокируем кнопку, если идет загрузка, чтобы не ломать стейт
                enabled = launchState is LaunchState.Idle || launchState is LaunchState.Error
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = "Новости",
                    tint = CelestiaTheme.colors.textSecondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ОСНОВНОЙ КОНТЕЙНЕР
        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {

                Text(
                    "ДОСТУПНЫЕ СЕРВЕРЫ",
                    style = MaterialTheme.typography.caption,
                    color = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 180.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)
                    ) {
                        val sortedServers = servers.sortedByDescending { favorites.contains(it.assetDir) }

                        items(sortedServers) { srv ->
                            val isSelected = srv == selectedServerState

                            SquareServerCard(
                                profile = srv,
                                isSelected = isSelected,
                                isFavorite = favorites.contains(srv.assetDir),
                                onSelect = {
                                    if (launchState is LaunchState.Idle || launchState is LaunchState.Error) {
                                        selectedServerState = srv
                                        onServerSelected(srv)
                                        profileManager.lastServerId = srv.assetDir
                                        profileManager.save()
                                    }
                                },
                                onLaunch = {
                                    if (selectedServerState != null && (launchState is LaunchState.Idle || launchState is LaunchState.Error)) {
                                        controller.launch(session, selectedServerState!!, onSessionUpdated)
                                    }
                                },
                                onSettings = { onOpenServerSettings(srv) },
                                onDetails = { onOpenDetails(srv) },
                                onToggleFav = {
                                    profileManager.toggleFavorite(srv.assetDir)
                                    favoriteTrigger++
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ПАНЕЛЬ ЗАПУСКА
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    LaunchControlPanel(
                        state = launchState,
                        onLaunch = {
                            if (selectedServerState != null) {
                                controller.launch(session, selectedServerState!!, onSessionUpdated)
                            }
                        },
                        onAbort = { controller.abort() },
                        onClearError = { controller.clearError() }
                    )
                }
            }
        }
    }
}
