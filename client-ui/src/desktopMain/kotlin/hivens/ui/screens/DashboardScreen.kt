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
import hivens.core.api.interfaces.IAuthService
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.ProfileManager
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.logic.LaunchUseCase
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Главный экран дашборда (Shell).
 *
 * <p>Отображает список доступных серверов, информацию о выбранном сервере
 * и кнопку запуска игры. Управляет процессом обновления сессии и запуска.</p>
 *
 * @param session Текущая активная сессия пользователя.
 * @param initialSelectedServer Сервер, выбранный по умолчанию (может быть null).
 * @param onServerSelected Callback при выборе сервера из списка.
 * @param onSessionUpdated Callback при обновлении сессии (например, ре-логин).
 * @param onCloseApp Callback для закрытия приложения.
 * @param onOpenServerSettings Переход к настройкам конкретного сервера.
 * @param onOpenNews Переход к экрану новостей.
 */
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
    // Внедрение зависимостей
    val serverListService: IServerListService = koinInject()
    val authService: IAuthService = koinInject()
    val profileManager: ProfileManager = koinInject()
    val credentialsManager: CredentialsManager = koinInject()
    val settingsService: ISettingsService = koinInject()
    val launchUseCase: LaunchUseCase = koinInject()

    // Состояние UI
    var servers by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServer by remember { mutableStateOf(initialSelectedServer) }

    var isLaunching by remember { mutableStateOf(false) }
    var isSyncingSession by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Готов к игре") }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 1. Первичная загрузка списка серверов
    LaunchedEffect(Unit) {
        if (servers.isEmpty()) {
            try {
                // Загружаем данные в IO потоке
                val data = withContext(Dispatchers.IO) {
                    serverListService.fetchDashboardData().get()
                }
                servers = data.servers

                // Восстанавливаем последний выбранный сервер, если не передан
                if (selectedServer == null) {
                    val lastId = profileManager.lastServerId
                    val default = servers.find { it.assetDir == lastId } ?: servers.firstOrNull()
                    if (default != null) {
                        selectedServer = default
                        onServerSelected(default)
                    }
                }
            } catch (e: Exception) {
                statusText = "Ошибка загрузки: ${e.message}"
            }
        }
    }

    // 2. Синхронизация сессии при смене сервера
    LaunchedEffect(selectedServer) {
        val srv = selectedServer ?: return@LaunchedEffect
        // Если текущая сессия выдана для другого сервера, нужно перелогиниться
        if (session.serverId != srv.assetDir) {
            isSyncingSession = true
            scope.launch(Dispatchers.IO) {
                try {
                    val creds = credentialsManager.load()
                    if (creds?.cachedPassword != null) {
                        val newSession = authService.login(session.playerName, creds.cachedPassword!!, srv.assetDir)
                        withContext(Dispatchers.Main) {
                            onSessionUpdated(newSession)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText = "Ошибка синхронизации: ${e.message}"
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isSyncingSession = false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Приветствие
        Text(
            text = "ДОБРО ПОЖАЛОВАТЬ, ${session.playerName.uppercase()}",
            style = MaterialTheme.typography.h5,
            color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(24.dp))

        // Основная карточка контента
        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.padding(32.dp)) {

                // Верхняя часть: Информация о сервере и кнопки действий
                Box(Modifier.weight(1f)) {
                    if (selectedServer != null) {
                        ServerHeader(
                            server = selectedServer!!,
                            isSyncing = isSyncingSession,
                            isLaunching = isLaunching,
                            onOpenNews = onOpenNews,
                            onSettings = { onOpenServerSettings(selectedServer!!) }
                        )
                    }
                }

                // Нижняя часть: Скролл серверов и кнопка запуска
                Text(
                    "ВЫБОР СЕРВЕРА",
                    style = MaterialTheme.typography.caption,
                    color = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))

                // Горизонтальный список серверов
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                            // Поддержка скролла мышью (для Desktop)
                            .onPointerEvent(PointerEventType.Scroll) {
                                val delta = it.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                                scope.launch { listState.scrollBy(delta.y * 50) }
                            },
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(servers) { srv ->
                            val isSelected = srv == selectedServer
                            ServerChip(srv.name, isSelected) {
                                if (!isLaunching) {
                                    selectedServer = srv
                                    onServerSelected(srv)
                                    // Сохраняем выбор
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

                // Панель запуска (Прогресс бар и Кнопка)
                LaunchControlPanel(
                    isLaunching = isLaunching,
                    isSyncing = isSyncingSession,
                    progress = progress,
                    statusText = statusText,
                    onLaunch = {
                        if (selectedServer != null) {
                            isLaunching = true
                            scope.launch {
                                try {
                                    launchUseCase.launch(
                                        currentSession = session,
                                        server = selectedServer!!,
                                        onProgress = { p, txt -> progress = p; statusText = txt },
                                        onSessionUpdated = { new -> onSessionUpdated(new) }
                                    )
                                    if (settingsService.getSettings().closeAfterStart) {
                                        onCloseApp()
                                    } else {
                                        statusText = "Игра запущена"; isLaunching = false; progress = 0f
                                    }
                                } catch (e: Exception) {
                                    statusText = "Ошибка: ${e.message}"; isLaunching = false
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

// --- Вспомогательные компоненты UI ---

@Composable
private fun ServerHeader(
    server: ServerProfile,
    isSyncing: Boolean,
    isLaunching: Boolean,
    onOpenNews: () -> Unit,
    onSettings: () -> Unit
) {
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
                if (isSyncing) {
                    Badge(text = "СИНХРОНИЗАЦИЯ...", color = CelestiaTheme.colors.textSecondary)
                } else {
                    Badge(text = "ONLINE", color = CelestiaTheme.colors.success)
                }
            }
        }

        IconButton(onClick = onOpenNews, enabled = !isLaunching) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
        }

        Spacer(Modifier.width(8.dp))

        IconButton(onClick = onSettings, enabled = !isLaunching) {
            Icon(Icons.Default.Settings, null, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun LaunchControlPanel(
    isLaunching: Boolean,
    isSyncing: Boolean,
    progress: Float,
    statusText: String,
    onLaunch: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (isLaunching || isSyncing) {
                Text(statusText, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                if (isLaunching) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.primary)
            }
        }
        Spacer(Modifier.height(4.dp))

        if (isLaunching || isSyncing) {
            LinearProgressIndicator(
                progress = if (isSyncing) 0f else progress,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                backgroundColor = CelestiaTheme.colors.surface,
                color = CelestiaTheme.colors.primary
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))

        CelestiaButton(
            text = when {
                isSyncing -> "СИНХРОНИЗАЦИЯ..."
                isLaunching -> "ЗАПУСК..."
                else -> "ИГРАТЬ"
            },
            enabled = !isLaunching && !isSyncing,
            onClick = onLaunch,
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered || isSelected) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 200)
    )

    val backgroundColor = if (isSelected) CelestiaTheme.colors.primary
    else if (isHovered) CelestiaTheme.colors.surface.copy(alpha = 0.9f)
    else CelestiaTheme.colors.surface

    Box(
        modifier = Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 100.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = if (isSelected) Color.Black else CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
