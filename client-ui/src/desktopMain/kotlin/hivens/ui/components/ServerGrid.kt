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
import hivens.ui.i18n.LocalStrings
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
                is GridItem.Server -> SquareServerCard(
                    profile = item.profile, isSelected = item.profile == selectedServer,
                    isFavorite = favorites.contains(item.profile.assetDir),
                    onSelect = { if (isLaunchable) onSelect(item.profile) },
                    onLaunch = { if (isLaunchable) onLaunch(item.profile) },
                    onSettings = { onSettings(item.profile) },
                    onDetails = { onDetails(item.profile) },
                    onToggleFav = { onToggleFav(item.profile) }
                )
            }
        }
    }
}

private sealed class GridItem {
    data class Header(val title: String) : GridItem()
    data class Server(val profile: ServerProfile) : GridItem()
}
