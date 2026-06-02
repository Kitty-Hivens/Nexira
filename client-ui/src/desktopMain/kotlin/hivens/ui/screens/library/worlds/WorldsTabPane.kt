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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import hivens.core.data.WorldDimension
import hivens.core.data.WorldEntry
import hivens.launcher.instance.ServersDatReader
import hivens.launcher.instance.WorldScanner
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64

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

    var worlds  by remember(instanceDir) { mutableStateOf<List<WorldEntry>?>(null) }
    var servers by remember(instanceDir) { mutableStateOf<List<MultiplayerServerEntry>?>(null) }

    LaunchedEffect(instanceDir) {
        val scanner = WorldScanner()
        val reader  = ServersDatReader()
        worlds = scanner.scan(instanceDir)
        servers = reader.read(instanceDir)
    }

    if (worlds == null || servers == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color       = CelestiaTheme.colors.primary.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier    = Modifier.size(28.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier            = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(text = s.worldsTabLocalSection(worlds!!.size))
        }
        if (worlds!!.isEmpty()) {
            item { EmptyHint(text = s.worldsTabLocalEmpty) }
        } else {
            items(items = worlds!!, key = { it.dirName }) { w -> WorldCard(world = w) }
        }

        item { Spacer(Modifier.height(4.dp)) }

        item {
            SectionHeader(text = s.worldsTabServersSection(servers!!.size))
        }
        if (servers!!.isEmpty()) {
            item { EmptyHint(text = s.worldsTabServersEmpty) }
        } else {
            items(items = servers!!, key = { it.ip + it.name }) { srv -> ServerCard(entry = srv) }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.titleSmall,
        color      = CelestiaTheme.colors.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(glassSurfaceAlpha(0.4f))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun WorldCard(world: WorldEntry) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(glassSurfaceAlpha(0.5f))
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorldThumb(iconPath = world.iconPath)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = world.displayName,
                style      = MaterialTheme.typography.bodyLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text  = lastPlayedLabel(world.lastPlayedEpochMs, s::worldsTabLastPlayed),
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                world.mcVersion?.let { v -> Chip("MC $v") }
                world.gameMode?.let { g -> Chip(gameModeLabel(g, s)) }
                world.dimensions.forEach { dim -> Chip(dimensionLabel(dim, s), accent = true) }
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
            .background(Color(0xFF1F2937)),
        contentAlignment = Alignment.Center,
    ) {
        if (iconPath != null) {
            AsyncImage(
                model              = File(iconPath),
                contentDescription = null,
                modifier           = Modifier.size(56.dp),
            )
        } else {
            Icon(
                imageVector        = Icons.Default.Public,
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
            .clip(RoundedCornerShape(12.dp))
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
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (entry.hidden) {
                    Icon(
                        imageVector        = Icons.Default.VisibilityOff,
                        contentDescription = s.worldsTabServerHiddenLabel,
                        tint               = CelestiaTheme.colors.textSecondary,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text  = entry.ip,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textSecondary,
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
            .background(Color(0xFF1F2937)),
        contentAlignment = Alignment.Center,
    ) {
        if (bytes != null) {
            AsyncImage(
                model              = bytes,
                contentDescription = null,
                modifier           = Modifier.size(48.dp),
            )
        } else {
            Icon(
                imageVector        = Icons.Default.Computer,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.55f),
                modifier           = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String, accent: Boolean = false) {
    AssistChip(
        onClick = {},
        enabled = false,
        shape   = RoundedCornerShape(6.dp),
        label   = {
            Text(
                text  = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (accent) Color.White else CelestiaTheme.colors.textSecondary,
            )
        },
        colors  = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (accent) CelestiaTheme.colors.primary.copy(alpha = 0.7f)
                                     else        CelestiaTheme.colors.outline.copy(alpha = 0.25f),
            disabledLabelColor     = if (accent) Color.White else CelestiaTheme.colors.textSecondary,
        ),
        border  = null,
    )
}

private fun lastPlayedLabel(epochMs: Long, formatter: (String) -> String): String {
    if (epochMs <= 0L) return formatter("—")
    val dur = Duration.between(Instant.ofEpochMilli(epochMs), Instant.now())
    val label = when {
        dur.toMinutes() < 1 -> "<1 min"
        dur.toHours() < 1   -> "${dur.toMinutes()}m"
        dur.toDays() < 1    -> "${dur.toHours()}h"
        dur.toDays() < 30   -> "${dur.toDays()}d"
        else                -> "${dur.toDays() / 30}mo"
    }
    return formatter(label)
}

private fun gameModeLabel(mode: GameMode, s: AppStrings): String = when (mode) {
    GameMode.Survival  -> s.worldsTabGameSurvival
    GameMode.Creative  -> s.worldsTabGameCreative
    GameMode.Adventure -> s.worldsTabGameAdventure
    GameMode.Spectator -> s.worldsTabGameSpectator
    GameMode.Unknown   -> s.worldsTabGameUnknown
}

private fun dimensionLabel(dim: WorldDimension, s: AppStrings): String = when (dim) {
    WorldDimension.Overworld -> s.worldsTabDimOverworld
    WorldDimension.Nether    -> s.worldsTabDimNether
    WorldDimension.End       -> s.worldsTabDimEnd
    WorldDimension.Other     -> s.worldsTabDimOther
}
