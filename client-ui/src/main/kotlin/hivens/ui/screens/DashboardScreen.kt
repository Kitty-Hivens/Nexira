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
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.ui.components.CelestiaButton
import hivens.ui.components.GlassCard
import hivens.ui.di
import hivens.ui.logic.LaunchUseCase
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DashboardScreen(
    session: SessionData,
    initialSelectedServer: ServerProfile?,
    onServerSelected: (ServerProfile) -> Unit,
    onSessionUpdated: (SessionData) -> Unit,
    onCloseApp: () -> Unit,
    onOpenServerSettings: (ServerProfile) -> Unit
) {
    var servers by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServer by remember { mutableStateOf(initialSelectedServer) }

    var isLaunching by remember { mutableStateOf(false) }
    var isSyncingSession by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Готов к игре") }

    val scope = rememberCoroutineScope()
    val launchUseCase = remember { LaunchUseCase(di) }

    val listState = rememberLazyListState()

    // 1. Загрузка списка серверов
    LaunchedEffect(Unit) {
        if (servers.isEmpty()) {
            val loaded = withContext(Dispatchers.IO) { di.serverListService.fetchProfiles().get() }
            servers = loaded

            if (selectedServer == null) {
                val lastId = di.profileManager.lastServerId
                val default = loaded.find { it.assetDir == lastId } ?: loaded.firstOrNull()
                if (default != null) {
                    selectedServer = default
                    onServerSelected(default)
                }
            }
        }
    }

    // 2. Авто-синхронизация сессии
    LaunchedEffect(selectedServer) {
        val srv = selectedServer ?: return@LaunchedEffect
        if (session.serverId != srv.assetDir) {
            isSyncingSession = true
            scope.launch(Dispatchers.IO) {
                try {
                    val creds = di.credentialsManager.load()
                    if (creds?.decryptedPassword != null) {
                        val newSession = di.authService.login(session.playerName, creds.decryptedPassword!!, srv.assetDir)
                        onSessionUpdated(newSession)
                    }
                } catch (e: Exception) {
                    statusText = "Ошибка: ${e.message}"
                } finally {
                    isSyncingSession = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "ДОБРО ПОЖАЛОВАТЬ, ${session.playerName.uppercase()}",
            style = MaterialTheme.typography.h5,
            color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(24.dp))

        GlassCard(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.padding(32.dp)) {

                // Верхний блок (Инфо)
                Box(Modifier.weight(1f)) {
                    if (selectedServer != null) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = selectedServer!!.title?.uppercase() ?: selectedServer!!.name,
                                    style = MaterialTheme.typography.h3,
                                    fontWeight = FontWeight.Black,
                                    color = CelestiaTheme.colors.textPrimary
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Badge(text = "VER: ${selectedServer!!.version}", color = CelestiaTheme.colors.primary)
                                    Spacer(Modifier.width(12.dp))
                                    if (isSyncingSession) {
                                        Badge(text = "СИНХРОНИЗАЦИЯ...", color = CelestiaTheme.colors.textSecondary)
                                    } else {
                                        Badge(text = "ONLINE", color = CelestiaTheme.colors.success)
                                    }
                                }
                            }

                            IconButton(
                                onClick = { onOpenServerSettings(selectedServer!!) },
                                enabled = !isLaunching
                            ) {
                                Icon(Icons.Default.Settings, null, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }

                // Нижний блок (Список и Запуск)
                Text("ВЫБОР СЕРВЕРА", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
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
                                    di.profileManager.setLastServerId(srv.assetDir)
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

                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (isLaunching || isSyncingSession) {
                            Text(statusText, style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.textSecondary)
                            if (isLaunching) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.caption, color = CelestiaTheme.colors.primary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    if (isLaunching || isSyncingSession) {
                        LinearProgressIndicator(
                            progress = if (isSyncingSession) 0f else progress,
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
                            isSyncingSession -> "СИНХРОНИЗАЦИЯ..."
                            isLaunching -> "ЗАПУСК..."
                            else -> "ИГРАТЬ"
                        },
                        enabled = !isLaunching && !isSyncingSession && selectedServer != null,
                        onClick = {
                            if (selectedServer == null) return@CelestiaButton
                            isLaunching = true

                            scope.launch {
                                try {
                                    launchUseCase.launch(
                                        currentSession = session,
                                        server = selectedServer!!,
                                        onProgress = { p, txt -> progress = p; statusText = txt },
                                        onSessionUpdated = { new -> onSessionUpdated(new) }
                                    )
                                    if (di.settingsService.getSettings().closeAfterStart) {
                                        onCloseApp()
                                    } else {
                                        statusText = "Игра запущена"; isLaunching = false; progress = 0f
                                    }
                                } catch (e: Exception) {
                                    statusText = "Ошибка: ${e.message}"; isLaunching = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                    )
                }
            }
        }
    }
}

// Helper components
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

    // Анимация масштаба
    val scale by animateFloatAsState(
        targetValue = if (isHovered || isSelected) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 200)
    )

    // Анимация прозрачности фона
    val backgroundColor = if (isSelected) CelestiaTheme.colors.primary
    else if (isHovered) CelestiaTheme.colors.surface.copy(alpha = 0.9f)
    else CelestiaTheme.colors.surface

    Box(
        modifier = Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 100.dp)
            // Применяем масштаб
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null // Убираем ripple, он тут лишний при наличии scale
            ) { onClick() }
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
