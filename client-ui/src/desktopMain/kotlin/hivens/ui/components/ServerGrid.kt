package hivens.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.model.ServerProfile
import hivens.launcher.AutoSyncService
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme

/**
 * Adaptive server grid with favorites section.
 */
@Composable
fun ServerGrid(
    servers: List<ServerProfile>,
    favorites: Set<String>,
    selectedServer: ServerProfile?,
    isLaunchable: Boolean,
    onSelect: (ServerProfile) -> Unit,
    onLaunch: (ServerProfile) -> Unit,
    onSettings: (ServerProfile) -> Unit,
    onDetails: (ServerProfile) -> Unit,
    onToggleFav: (ServerProfile) -> Unit,
    syncStates: Map<String, AutoSyncService.ServerState> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val favoriteServers = servers.filter { favorites.contains(it.assetDir) }
    val regularServers = servers.filter { !favorites.contains(it.assetDir) }
    val hasFavorites = favoriteServers.isNotEmpty()

    val items = buildList {
        if (hasFavorites) {
            add(GridItem.Header(s.serversFavorites))
            favoriteServers.forEach { add(GridItem.Server(it)) }
            if (regularServers.isNotEmpty()) {
                add(GridItem.Header(s.dashboardServers))
            }
        }
        regularServers.forEach { add(GridItem.Server(it)) }
    }

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            Text(s.dashboardServersEmpty, color = CelestiaTheme.colors.textSecondary, modifier = Modifier.padding(20.dp))
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = modifier.fillMaxSize()
    ) {
        items(
            count = items.size,
            key = { when (val item = items[it]) { is GridItem.Header -> "header_${item.title}"; is GridItem.Server -> "server_${item.profile.assetDir}" } },
            span = { when (items[it]) { is GridItem.Header -> GridItemSpan(maxLineSpan); is GridItem.Server -> GridItemSpan(1) } }
        ) { index ->
            when (val item = items[index]) {
                is GridItem.Header -> Text(
                    item.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f), letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = if (index > 0) 8.dp else 0.dp, bottom = 4.dp)
                )
                is GridItem.Server -> {
                    SquareServerCard(
                        profile = item.profile, isSelected = item.profile == selectedServer,
                        isFavorite = favorites.contains(item.profile.assetDir),
                        onSelect = { if (isLaunchable) onSelect(item.profile) },
                        onLaunch = { if (isLaunchable) onLaunch(item.profile) },
                        onSettings = { onSettings(item.profile) },
                        onDetails = { onDetails(item.profile) },
                        onToggleFav = { onToggleFav(item.profile) },
                        syncState = syncStates[item.profile.assetDir]
                    )
                    // Puppet: per-card actions keyed by assetDir so drivers can
                    // pick a specific server by name (e.g. "SkyBlock") instead
                    // of having to know its grid index. Only registered while
                    // the card is composed (LazyVerticalGrid only composes
                    // visible items) — off-screen cards won't be reachable
                    // until scrolled into view. Not a problem with Aura's
                    // current 7-server inventory; document if it grows.
                    val asset = item.profile.assetDir
                    PuppetClick("server.select.$asset", enabled = isLaunchable) { onSelect(item.profile) }
                    PuppetClick("server.launch.$asset", enabled = isLaunchable) { onLaunch(item.profile) }
                    PuppetClick("server.details.$asset") { onDetails(item.profile) }
                    PuppetClick("server.settings.$asset") { onSettings(item.profile) }
                    PuppetClick("server.toggleFav.$asset") { onToggleFav(item.profile) }
                }
            }
        }
    }
}

private sealed class GridItem {
    data class Header(val title: String) : GridItem()
    data class Server(val profile: ServerProfile) : GridItem()
}
