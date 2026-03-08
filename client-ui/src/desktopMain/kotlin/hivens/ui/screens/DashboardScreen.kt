package hivens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
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
import hivens.ui.components.LaunchControlPanel
import hivens.ui.components.SquareServerCard
import hivens.ui.i18n.LocalStrings
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
    val settingsService: ISettingsService     = koinInject()
    val profileManager: ProfileManager        = koinInject()
    val controller: LauncherController        = koinInject()
    val s = LocalStrings.current

    val launchState by controller.state.collectAsState()

    var servers             by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServerState by remember { mutableStateOf(initialSelectedServer) }
    var favoriteTrigger     by remember { mutableStateOf(0) }
    val favorites = remember(favoriteTrigger) { profileManager.favoriteServers }

    LaunchedEffect(launchState) {
        if (launchState is LaunchState.GameRunning) {
            if (settingsService.getSettings().closeAfterStart) onCloseApp()
        }
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty()) {
            try {
                val data = withContext(Dispatchers.IO) { serverListService.fetchDashboardData().get() }
                servers = data.servers

                if (selectedServerState == null) {
                    val lastId  = profileManager.lastServerId
                    val default = servers.find { it.assetDir == lastId } ?: servers.firstOrNull()
                    if (default != null) { selectedServerState = default; onServerSelected(default) }
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(initialSelectedServer) {
        if (initialSelectedServer != null) selectedServerState = initialSelectedServer
    }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {

        // ── Header ────────────────────────────────────────────────────────────
        Text(
            text      = s.dashboardWelcome(session.playerName),
            style     = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Medium,
            color     = CelestiaTheme.colors.textSecondary
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text      = s.dashboardServers,
            style     = MaterialTheme.typography.caption,
            color     = CelestiaTheme.colors.textSecondary.copy(alpha = 0.55f),
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        // ── Server grid ───────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement   = Arrangement.spacedBy(14.dp),
                contentPadding        = PaddingValues(bottom = 8.dp),
                modifier              = Modifier.fillMaxSize()
            ) {
                val sortedServers = servers.sortedByDescending { favorites.contains(it.assetDir) }
                items(sortedServers) { srv ->
                    SquareServerCard(
                        profile    = srv,
                        isSelected = srv == selectedServerState,
                        isFavorite = favorites.contains(srv.assetDir),
                        onSelect   = {
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
                        onSettings  = { onOpenServerSettings(srv) },
                        onDetails   = { onOpenDetails(srv) },
                        onToggleFav = { profileManager.toggleFavorite(srv.assetDir); favoriteTrigger++ }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Launch control ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
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
