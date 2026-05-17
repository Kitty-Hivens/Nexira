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
import hivens.launcher.AutoSyncService
import hivens.launcher.network.NetworkState
import hivens.launcher.ProfileManager
import hivens.ui.components.LaunchControlPanel
import hivens.ui.components.ServerGrid
import hivens.ui.i18n.LocalStrings
import hivens.ui.logic.LaunchState
import hivens.ui.logic.LauncherController
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    PuppetScreen("Dashboard")

    val serverListService: IServerListService = koinInject()
    val settingsService: ISettingsService     = koinInject()
    val profileManager: ProfileManager        = koinInject()
    val controller: LauncherController        = koinInject()
    val autoSyncService: AutoSyncService      = koinInject()
    val protocolConfig: hivens.launcher.network.ServerProtocolConfig = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val launchState by controller.state.collectAsState()
    val syncStates by autoSyncService.serverStates.collectAsState()
    val syncOverall by autoSyncService.overallState.collectAsState()
    var hiddenForCurrentSession by remember { mutableStateOf(false) }

    var servers             by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServerState by remember { mutableStateOf(initialSelectedServer) }
    var favoriteTrigger     by remember { mutableStateOf(0) }
    val favorites = remember(favoriteTrigger) { profileManager.favoriteServers }
    var isLoadingServers    by remember { mutableStateOf(true) }
    val bypassHost = protocolConfig.sslBypassHost
    val sslBypass by produceState(initialValue = NetworkState.bypassFor(bypassHost), bypassHost) {
        while (true) {
            value = NetworkState.bypassFor(bypassHost)
            delay(200.milliseconds)
        }
    }


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
                org.slf4j.LoggerFactory.getLogger("DashboardScreen")
                    .error("Failed to load server list", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingServers = false
                }
            }
        }
    }

    LaunchedEffect(launchState) {
        when (launchState) {
            is LaunchState.GameRunning -> {
                if (settingsService.getSettings().closeAfterStart && !hiddenForCurrentSession) {
                    hiddenForCurrentSession = true
                    onCloseApp()
                }
            }
            is LaunchState.Idle, is LaunchState.Error -> {
                hiddenForCurrentSession = false
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty()) fetchServers()
        else isLoadingServers = false
    }

    LaunchedEffect(sslBypass) {
        if (sslBypass && servers.isEmpty()) {
            fetchServers()
        }
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
                        },
                        syncStates = syncStates
                    )
                }
            }
        }

        // ── Auto-sync overall progress strip ──────────────────────────────────
        // Sticky just above the launch control panel. Visible only while
        // AutoSyncService is actively walking the queue. Stays out of the way
        // when auto-sync is disabled or has finished.
        if (syncOverall is AutoSyncService.OverallState.InProgress) {
            val progress = syncOverall as AutoSyncService.OverallState.InProgress
            Spacer(Modifier.height(8.dp))
            AutoSyncProgressStrip(
                serverName = progress.currentServer,
                currentIdx = progress.currentIdx,
                total      = progress.total,
                bytesRead  = progress.bytesRead,
                totalBytes = progress.totalBytes,
            )
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

/**
 * Compact strip above the launch panel showing AutoSyncService progress.
 * Renders only while a sync is in-flight; auto-hides when state transitions
 * to Idle / Done. Kept self-contained (no Koin deps) so it can be tested
 * with a fake `InProgress` state.
 */
@Composable
private fun AutoSyncProgressStrip(
    serverName: String,
    currentIdx: Int,
    total: Int,
    bytesRead: Long,
    totalBytes: Long,
) {
    val s = LocalStrings.current
    val progressFraction = if (totalBytes > 0) {
        (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = CelestiaTheme.colors.outline.copy(alpha = 0.20f),
                shape = RoundedCornerShape(10.dp)
            )
            .background(
                color = CelestiaTheme.colors.surface.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s.dashboardAutoSyncProgress(serverName, currentIdx, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium
                )
                if (totalBytes > 0) {
                    Text(
                        text = s.dashboardAutoSyncBytes(bytesRead / 1_048_576, totalBytes / 1_048_576),
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth(),
                color = CelestiaTheme.colors.primary,
                trackColor = CelestiaTheme.colors.outline.copy(alpha = 0.15f),
            )
        }
    }
}
