package hivens.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.ProfileManager
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.logic.LaunchState
import hivens.ui.logic.LauncherController
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.DecimalFormat

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DashboardScreen(
    session: SessionData,
    initialSelectedServer: ServerProfile?,
    onServerSelected: (ServerProfile) -> Unit,
    onSessionUpdated: (SessionData) -> Unit,
    onCloseApp: () -> Unit,
    onOpenServerSettings: (ServerProfile) -> Unit,
    onOpenNews: () -> Unit
) {
    // ЗАВИСИМОСТИ
    val serverListService: IServerListService = koinInject()
    val settingsService: ISettingsService = koinInject()
    val profileManager: ProfileManager = koinInject()
    val controller: LauncherController = koinInject()

    // СОСТОЯНИЕ
    val launchState by controller.state.collectAsState()

    var servers by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServerState by remember { mutableStateOf(initialSelectedServer) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
        // ПРИВЕТСТВИЕ
        Text(
            text = "ДОБРО ПОЖАЛОВАТЬ, ${session.playerName.uppercase()}",
            style = MaterialTheme.typography.h5,
            color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(24.dp))

        // ОСНОВНАЯ КАРТОЧКА
        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.padding(32.dp)) {

                // ИНФО О СЕРВЕРЕ
                Box(Modifier.weight(1f)) {
                    if (selectedServerState != null) {
                        ServerHeader(
                            server = selectedServerState!!,
                            launchState = launchState,
                            onOpenNews = onOpenNews,
                            onSettings = { onOpenServerSettings(selectedServerState!!) }
                        )
                    }
                }

                // СПИСОК СЕРВЕРОВ
                Text(
                    "ВЫБОР СЕРВЕРА",
                    style = MaterialTheme.typography.caption,
                    color = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))

                // Заменяем Box(height=60) на более гибкий контейнер
                Box(Modifier.fillMaxWidth().height(80.dp)) { // Чуть выше
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), // Меньше отступ
                        contentPadding = PaddingValues(bottom = 8.dp) // Отступ для скроллбара
                    ) {
                        items(servers) { srv ->
                            val isSelected = srv == selectedServerState
                            // Более компактный чип
                            ServerChip(srv.name, isSelected) {
                                if (launchState is LaunchState.Idle || launchState is LaunchState.Error) {
                                    selectedServerState = srv
                                    onServerSelected(srv)
                                    profileManager.lastServerId = srv.assetDir
                                    profileManager.save()
                                }
                            }
                        }
                    }
                    HorizontalScrollbar(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        adapter = rememberScrollbarAdapter(listState),
                        style = ScrollbarStyle(
                            minimalHeight = 4.dp, thickness = 4.dp, shape = RoundedCornerShape(2.dp),
                            hoverDurationMillis = 300,
                            unhoverColor = CelestiaTheme.colors.surface.copy(alpha = 0.5f),
                            hoverColor = CelestiaTheme.colors.primary
                        )
                    )
                }

                Spacer(Modifier.height(32.dp))

                // ПАНЕЛЬ ЗАПУСКА
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

// --- КОМПОНЕНТЫ ---

@Composable
private fun ServerHeader(
    server: ServerProfile,
    launchState: LaunchState,
    onOpenNews: () -> Unit,
    onSettings: () -> Unit
) {
    val isLocked = launchState !is LaunchState.Idle && launchState !is LaunchState.Error

    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = server.title?.uppercase() ?: server.name,
                style = MaterialTheme.typography.h3,
                fontWeight = FontWeight.Black,
                color = CelestiaTheme.colors.textPrimary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(text = "VER: ${server.version}", color = CelestiaTheme.colors.primary)
                Spacer(Modifier.width(12.dp))
                Badge(text = "ONLINE", color = CelestiaTheme.colors.success)
            }
        }

        IconButton(onClick = onOpenNews, enabled = !isLocked) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSettings, enabled = !isLocked) {
            Icon(Icons.Default.Settings, null, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun LaunchControlPanel(
    state: LaunchState,
    onLaunch: () -> Unit,
    onAbort: () -> Unit,
    onClearError: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // СТАТУС БАР
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            when (state) {
                is LaunchState.Idle -> Text("Готов к игре", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                is LaunchState.Prepare -> Text(state.stepName, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                is LaunchState.Downloading -> {
                    val downloadedMb = state.downloadedBytes / 1024.0 / 1024.0
                    val totalMb = state.totalBytes / 1024.0 / 1024.0
                    val format = DecimalFormat("#0.0")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Загрузка: ", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                        Text(
                            "${format.format(downloadedMb)} / ${format.format(totalMb)} MB (${state.speedStr})",
                            style = MaterialTheme.typography.caption,
                            color = CelestiaTheme.colors.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is LaunchState.Error -> Text(state.message, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.error)
                is LaunchState.GameRunning -> Text("Игра запущена", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.success)
            }
        }

        Spacer(Modifier.height(4.dp))

        // ПРОГРЕСС БАР
        val progress = when(state) {
            is LaunchState.Prepare -> state.progress
            is LaunchState.Downloading -> state.progress
            is LaunchState.GameRunning -> 1.0f
            else -> 0f
        }

        if (state !is LaunchState.Idle && state !is LaunchState.Error) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                backgroundColor = CelestiaTheme.colors.surface,
                color = CelestiaTheme.colors.primary
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))

        // КНОПКА ДЕЙСТВИЯ
        val btnText = when (state) {
            is LaunchState.Downloading, is LaunchState.Prepare -> "ОТМЕНА"
            is LaunchState.GameRunning -> "ЗАПУЩЕНО"
            is LaunchState.Error -> "СБРОСИТЬ ОШИБКУ" // Кнопка меняется при ошибке
            else -> "ИГРАТЬ"
        }

        CelestiaButton(
            text = btnText,
            enabled = state !is LaunchState.GameRunning,
            onClick = {
                when (state) {
                    is LaunchState.Downloading, is LaunchState.Prepare -> onAbort()
                    is LaunchState.Error -> onClearError()
                    else -> onLaunch()
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        )
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Box(modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.overline, color = color)
    }
}

@Composable
fun ServerChip(name: String, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) CelestiaTheme.colors.primary else CelestiaTheme.colors.surface
    val contentColor = if (isSelected) Color.White else CelestiaTheme.colors.textPrimary

    Box(
        modifier = Modifier
            .height(32.dp) // Было 40, стало компактнее
            .clip(RoundedCornerShape(8.dp)) // Меньше скругление
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.caption // Меньший шрифт
        )
    }
}
