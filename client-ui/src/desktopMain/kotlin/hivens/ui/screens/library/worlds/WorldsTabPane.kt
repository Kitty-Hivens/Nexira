package hivens.ui.screens.library.worlds

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.data.GameMode
import hivens.core.data.MultiplayerServerEntry
import hivens.core.data.WorldEntry
import hivens.launcher.instance.ServersDatReader
import hivens.launcher.instance.WorldScanner
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxSectionHeader
import hivens.ui.nx.RetryStateBlock
import hivens.ui.theme.NxTheme
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library PackDetail Worlds tab. Two stacked sections:
 *  - Local worlds scanned from `<instanceDir>/saves/` (cards with
 *    icon.png thumbnail, name, dimensions, last-played, MC version).
 *  - Multiplayer server history from `<instanceDir>/servers.dat`
 *    (player's vanilla "Add Server" list, base64 icons decoded).
 *
 * Read-only inspection. No delete / rename / launch-directly actions
 * in this pass; those land in a follow-up once we agree on what the
 * Library "open this world" flow even looks like (vanilla MC opens
 * the singleplayer world picker, not a specific world by path).
 */
@Composable
fun WorldsTabPane(instanceDir: Path, modifier: Modifier = Modifier) {
    val s = LocalStrings.current

    var state by remember(instanceDir) { mutableStateOf<WorldsState>(WorldsState.Loading) }
    var retryTick by remember(instanceDir) { mutableIntStateOf(0) }

    // Off-thread scan: a corrupt servers.dat / malformed NBT / permission error
    // throws, so a bare assignment would leave the pane on an endless spinner.
    LaunchedEffect(instanceDir, retryTick) {
        state = WorldsState.Loading
        state = runCatching {
            withContext(Dispatchers.IO) {
                WorldsState.Loaded(
                    worlds  = WorldScanner().scan(instanceDir),
                    servers = ServersDatReader().read(instanceDir),
                )
            }
        }.getOrElse { WorldsState.Error }
    }

    when (val st = state) {
        WorldsState.Loading -> CenteredProgress(modifier.fillMaxSize())
        WorldsState.Error -> RetryStateBlock(
            title      = s.worldsTabErrorTitle,
            message    = s.worldsTabErrorMessage,
            retryLabel = s.contentTabRetry,
            onRetry    = { retryTick++ },
            modifier   = modifier.fillMaxSize().padding(20.dp),
        )
        is WorldsState.Loaded -> WorldsList(worlds = st.worlds, servers = st.servers, modifier = modifier)
    }
}

private sealed interface WorldsState {
    data object Loading : WorldsState
    data object Error : WorldsState
    data class Loaded(
        val worlds: List<WorldEntry>,
        val servers: List<MultiplayerServerEntry>,
    ) : WorldsState
}

@Composable
private fun WorldsList(
    worlds: List<WorldEntry>,
    servers: List<MultiplayerServerEntry>,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    LazyColumn(
        modifier            = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { NxSectionHeader(text = s.worldsTabLocalSection(worlds.size)) }
        if (worlds.isEmpty()) {
            item { EmptyHint(text = s.worldsTabLocalEmpty) }
        } else {
            items(items = worlds, key = { it.dirName }) { w -> WorldCard(world = w) }
        }

        item { Spacer(Modifier.height(4.dp)) }

        item { NxSectionHeader(text = s.worldsTabServersSection(servers.size)) }
        if (servers.isEmpty()) {
            item { EmptyHint(text = s.worldsTabServersEmpty) }
        } else {
            items(items = servers, key = { it.ip + it.name }) { srv -> ServerCard(entry = srv) }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.4f))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun WorldCard(world: WorldEntry) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.5f))
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorldThumb(iconPath = world.iconPath)

        // Minecraft's own world-list layout: icon, name, then two muted lines
        // (folder + last-played date, game mode + version) instead of a row of
        // accent chips -- "Overworld" sat on nearly every world and read as noise.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text       = world.displayName,
                style      = MaterialTheme.typography.bodyLarge,
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = worldSubtitle(world),
                style    = MaterialTheme.typography.labelSmall,
                color    = NxTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val info = worldInfoLine(world, s)
            if (info.isNotBlank()) {
                Text(
                    text     = info,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = NxTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorldThumb(iconPath: String?) {
    Box(
        modifier         = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NxTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (iconPath != null) {
            AsyncImage(
                model              = File(iconPath),
                contentDescription = null,
                modifier           = Modifier.size(56.dp),
            )
        } else {
            Symbol(icon = NxIcon.Public,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.55f),
                modifier           = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ServerCard(entry: MultiplayerServerEntry) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.45f))
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServerThumb(iconBase64 = entry.iconBase64)

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text       = entry.name,
                    style      = MaterialTheme.typography.bodyLarge,
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (entry.hidden) {
                    Symbol(icon = NxIcon.VisibilityOff,
                        contentDescription = s.worldsTabServerHiddenLabel,
                        tint               = NxTheme.colors.textSecondary,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text  = entry.ip,
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ServerThumb(iconBase64: String?) {
    val bytes = remember(iconBase64) {
        if (iconBase64.isNullOrBlank()) null
        else runCatching { Base64.getDecoder().decode(iconBase64) }.getOrNull()
    }
    Box(
        modifier         = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NxTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bytes != null) {
            AsyncImage(
                model              = bytes,
                contentDescription = null,
                modifier           = Modifier.size(48.dp),
            )
        } else {
            Symbol(icon = NxIcon.Computer,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.55f),
                modifier           = Modifier.size(24.dp),
            )
        }
    }
}

/** Second line, MC-style: "<folder>  ·  <last-played date>" (date omitted if unknown). */
private fun worldSubtitle(world: WorldEntry): String {
    val date = world.lastPlayedEpochMs
        .takeIf { it > 0L }
        ?.let { WORLD_DATE_FMT.format(Instant.ofEpochMilli(it)) }
    return listOfNotNull(world.dirName, date).joinToString("  ·  ")
}

/** Third line, MC-style: "<game mode>  ·  <version>" (each part dropped when absent). */
private fun worldInfoLine(world: WorldEntry, s: AppStrings): String =
    listOfNotNull(world.gameMode?.let { gameModeLabel(it, s) }, world.mcVersion)
        .joinToString("  ·  ")

private val WORLD_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

private fun gameModeLabel(mode: GameMode, s: AppStrings): String = when (mode) {
    GameMode.Survival  -> s.worldsTabGameSurvival
    GameMode.Creative  -> s.worldsTabGameCreative
    GameMode.Adventure -> s.worldsTabGameAdventure
    GameMode.Spectator -> s.worldsTabGameSpectator
    GameMode.Unknown   -> s.worldsTabGameUnknown
}
