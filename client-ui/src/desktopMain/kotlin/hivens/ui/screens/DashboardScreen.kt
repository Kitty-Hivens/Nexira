package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import hivens.launcher.ProfileManager
import hivens.ui.components.LaunchControlPanel
import hivens.ui.components.ServerGrid
import hivens.ui.i18n.LocalStrings
import hivens.ui.logic.LaunchState
import hivens.ui.logic.LauncherController
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    onOpenDetails: (ServerProfile) -> Unit
) {
    val serverListService: IServerListService = koinInject()
    val settingsService: ISettingsService     = koinInject()
    val profileManager: ProfileManager        = koinInject()
    val controller: LauncherController        = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val launchState by controller.state.collectAsState()

    var servers             by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServerState by remember { mutableStateOf(initialSelectedServer) }
    var favoriteTrigger     by remember { mutableStateOf(0) }
    val favorites = remember(favoriteTrigger) { profileManager.favoriteServers }
    var isLoadingServers    by remember { mutableStateOf(true) }

    fun fetchServers() {
        isLoadingServers = true
        scope.launch(Dispatchers.IO) {
            try {
                val data = serverListService.fetchDashboardData().get()
                withContext(Dispatchers.Main) {
                    servers = data.servers
                    if (selectedServerState == null && servers.isNotEmpty()) {
                        val lastId  = profileManager.lastServerId
                        val default = servers.find { it.assetDir == lastId } ?: servers.firstOrNull()
                        if (default != null) {
                            selectedServerState = default
                            onServerSelected(default)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingServers = false
                }
            }
        }
    }

    LaunchedEffect(launchState) {
        if (launchState is LaunchState.GameRunning) {
            if (settingsService.getSettings().closeAfterStart) onCloseApp()
        }
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty()) fetchServers()
        else isLoadingServers = false
    }

    LaunchedEffect(initialSelectedServer) {
        if (initialSelectedServer != null) selectedServerState = initialSelectedServer
    }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {

        // ── Header ────────────────────────────────────────────────────────────
        Text(
            text       = s.dashboardWelcome(session.playerName),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color      = CelestiaTheme.colors.textSecondary
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text       = s.dashboardServers,
            style      = MaterialTheme.typography.bodySmall,
            color      = CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        // ── Server grid (replaces raw LazyVerticalGrid) ───────────────────
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                isLoadingServers -> {
                    CircularProgressIndicator(color = CelestiaTheme.colors.primary)
                }
                servers.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector        = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint               = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f),
                            modifier           = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            s.dashboardServersEmpty,
                            color = CelestiaTheme.colors.textSecondary
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { fetchServers() },
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = CelestiaTheme.colors.primary
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s.updateRetry)
                        }
                    }
                }
                else -> {
                    ServerGrid(
                        servers        = servers,
                        favorites      = favorites,
                        selectedServer = selectedServerState,
                        isLaunchable   = launchState is LaunchState.Idle || launchState is LaunchState.Error,
                        onSelect       = { srv ->
                            selectedServerState = srv
                            onServerSelected(srv)
                            profileManager.lastServerId = srv.assetDir
                            profileManager.save()
                        },
                        onLaunch = { srv ->
                            selectedServerState = srv
                            onServerSelected(srv)
                            controller.launch(session, srv, onSessionUpdated)
                        },
                        onSettings  = { onOpenServerSettings(it) },
                        onDetails   = { onOpenDetails(it) },
                        onToggleFav = {
                            profileManager.toggleFavorite(it.assetDir)
                            favoriteTrigger++
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Launch control ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = CelestiaTheme.colors.outline.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(14.dp)
                )
                .background(
                    color = CelestiaTheme.colors.surface.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            LaunchControlPanel(
                state        = launchState,
                onLaunch     = { selectedServerState?.let { controller.launch(session, it, onSessionUpdated) } },
                onAbort      = { controller.abort() },
                onClearError = { controller.clearError() }
            )
        }
    }
}
