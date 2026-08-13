package hivens.ui.widgets.home.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import hivens.core.launch.LaunchState
import hivens.launcher.AutoSyncService
import hivens.launcher.ProfileManager
import hivens.launcher.launch.LauncherController
import hivens.core.security.SslBypassStore
import hivens.ui.components.LaunchControlPanel
import hivens.ui.components.ServerGrid
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// Monolithic dashboard widget. Wraps the entire legacy DashboardScreen
// body verbatim -- the regions (header / server grid / sync strip /
// launch panel) share too much local state for clean extraction. The
// classic dashboard is transitional and slated for removal once the
// new widget-composed home matures; we widgetize it as one block so
// the slot machinery is exercised without paying the refactor cost on
// code that's going away.
@Widget(id = "home.classic.content", displayName = "widget.home.classic.content")
@Composable
fun HomeClassicContent(instance: WidgetInstance) {
    val ctx = LocalHomeClassicContext.current
    val serverListService: IServerListService = koinInject()
    val profileManager: ProfileManager = koinInject()
    val controller: LauncherController = koinInject()
    val launchDriver: LaunchDriver = koinInject()
    val autoSyncService: AutoSyncService = koinInject()
    val protocolConfig: hivens.launcher.network.ServerProtocolConfig = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val launchState by controller.state.collectAsState()
    val syncSnapshot by autoSyncService.snapshot.collectAsState()
    val syncStates = syncSnapshot.perServer
    val syncOverall = syncSnapshot.overall

    var servers by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var selectedServerState by remember { mutableStateOf(ctx.initialSelectedServer) }
    var favoriteTrigger by remember { mutableStateOf(0) }
    val favorites = remember(favoriteTrigger) { profileManager.favoriteServers }
    var isLoadingServers by remember { mutableStateOf(true) }
    val bypassHost = protocolConfig.sslBypassHost
    val bypassStore: SslBypassStore = koinInject()
    val bypassesList by bypassStore.bypasses.collectAsState()
    val sslBypass = remember(bypassesList, bypassHost) { bypassStore.isBypassed(bypassHost) }

    fun fetchServers(forceRefresh: Boolean = false) {
        isLoadingServers = true
        scope.launch(Dispatchers.IO) {
            try {
                val data = runInterruptible {
                    if (forceRefresh) serverListService.refresh().get()
                    else              serverListService.fetchDashboardData().get()
                }
                withContext(Dispatchers.Main) {
                    servers = data.servers
                    // Re-resolve the selection against the list that just arrived,
                    // rather than only seeding it when there is none. The selection
                    // is held above this screen for the process lifetime, so what
                    // sits in it is a profile from whichever fetch first produced
                    // it -- and Play launches THAT one, address, version, checksums
                    // and all, however many times the roster has changed since.
                    val wanted = selectedServerState?.assetDir ?: profileManager.lastServerId
                    // Falls back to the first entry only when nothing is selected
                    // yet: a fetch that comes back without the selected server --
                    // a partial roster, a bad response -- must not quietly move
                    // the selection onto a different server and launch that one.
                    val resolved = servers.find { it.assetDir == wanted }
                        ?: servers.firstOrNull().takeIf { selectedServerState == null }
                    if (resolved != null && resolved != selectedServerState) {
                        selectedServerState = resolved
                        ctx.onServerSelected(resolved)
                    }
                }
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger("HomeClassicContent")
                    .error("Failed to load server list", e)
            } finally {
                withContext(Dispatchers.Main) { isLoadingServers = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty()) fetchServers() else isLoadingServers = false
    }

    LaunchedEffect(sslBypass) {
        if (sslBypass && servers.isEmpty()) fetchServers()
    }

    LaunchedEffect(ctx.initialSelectedServer) {
        if (ctx.initialSelectedServer != null) selectedServerState = ctx.initialSelectedServer
    }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
        Text(
            text       = s.dashboardWelcome(ctx.session.playerName),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color      = NxTheme.colors.textSecondary,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text       = s.dashboardServers,
            style      = MaterialTheme.typography.bodySmall,
            color      = NxTheme.colors.textSecondary.copy(alpha = 0.55f),
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                isLoadingServers -> CircularProgressIndicator(color = NxTheme.colors.primary)
                servers.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Symbol(icon = NxIcon.WifiOff,
                            contentDescription = null,
                            tint               = NxTheme.colors.textSecondary.copy(alpha = 0.5f),
                            modifier           = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(s.dashboardServersEmpty, color = NxTheme.colors.textSecondary)
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { fetchServers(forceRefresh = true) },
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = NxTheme.colors.primary,
                            ),
                        ) {
                            Symbol(NxIcon.Refresh, contentDescription = null)
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
                            ctx.onServerSelected(srv)
                            profileManager.lastServerId = srv.assetDir
                            profileManager.save()
                        },
                        onLaunch = { srv ->
                            selectedServerState = srv
                            ctx.onServerSelected(srv)
                            if (controller.launch(ctx.session, srv, ctx.onSessionUpdated)) {
                                launchDriver.observe(LaunchTarget.Server(srv))
                            }
                        },
                        onSettings  = { ctx.onOpenServerSettings(it) },
                        onDetails   = { ctx.onOpenDetails(it) },
                        onToggleFav = {
                            profileManager.toggleFavorite(it.assetDir)
                            favoriteTrigger++
                        },
                        syncStates = syncStates,
                    )
                }
            }
        }

        if (syncOverall is AutoSyncService.OverallState.InProgress) {
            Spacer(Modifier.height(8.dp))
            AutoSyncProgressStrip(
                serverName = syncOverall.currentServer,
                currentIdx = syncOverall.currentIdx,
                total      = syncOverall.total,
                bytesRead  = syncOverall.bytesRead,
                totalBytes = syncOverall.totalBytes,
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = NxTheme.colors.outline.copy(alpha = 0.25f),
                    shape = MaterialTheme.shapes.medium,
                )
                .background(
                    color = glassSurfaceAlpha(0.45f),
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            LaunchControlPanel(
                state        = launchState,
                onLaunch     = {
                    selectedServerState?.let { srv ->
                        if (controller.launch(ctx.session, srv, ctx.onSessionUpdated)) {
                            launchDriver.observe(LaunchTarget.Server(srv))
                        }
                    }
                },
                onAbort      = { controller.abort() },
            )
        }
    }
}

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
                color = NxTheme.colors.outline.copy(alpha = 0.20f),
                shape = MaterialTheme.shapes.medium,
            )
            .background(
                color = glassSurfaceAlpha(0.35f),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text       = s.dashboardAutoSyncProgress(serverName, currentIdx, total),
                    style      = MaterialTheme.typography.bodySmall,
                    color      = NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
                if (totalBytes > 0) {
                    Text(
                        text  = s.dashboardAutoSyncBytes(bytesRead / 1_048_576, totalBytes / 1_048_576),
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress   = { progressFraction },
                modifier   = Modifier.fillMaxWidth(),
                color      = NxTheme.colors.primary,
                trackColor = NxTheme.colors.outline.copy(alpha = 0.15f),
            )
        }
    }
}
