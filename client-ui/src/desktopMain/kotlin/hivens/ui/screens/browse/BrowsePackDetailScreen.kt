package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.dto.smrt.SmrtSource
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Catalogue detail screen: shows the full manifest of a pack served
 * by the mirror so a user can inspect what would be installed before
 * committing to it. Read-only; the actual install pipeline (creates
 * a [hivens.core.data.PackInstance], calls
 * [hivens.launcher.smrt.SmrtSyncService.sync]) lands in the next PR.
 *
 * Three states:
 *  - [DetailState.Loading] -- summary + manifest in flight in parallel
 *  - [DetailState.Loaded]  -- both succeeded
 *  - [DetailState.Error]   -- either call failed; retry available
 */
@Composable
fun BrowsePackDetailScreen(packId: String, onBack: () -> Unit) {
    PuppetScreen("BrowsePackDetail.$packId")
    PuppetClick("browse.detail.back") { onBack() }

    val s = LocalStrings.current
    val client: SmrtPackClient = koinInject()
    var state by remember(packId) { mutableStateOf<DetailState>(DetailState.Loading) }
    var retryTick by remember { mutableIntStateOf(0) }

    PuppetClick("browse.detail.retry") { retryTick++ }

    LaunchedEffect(packId, retryTick) {
        state = DetailState.Loading
        state = try {
            // Summary and manifest both come from independent endpoints;
            // fetch in parallel so the wall-time is one round-trip, not two.
            val pair: Pair<SmrtPackSummary, SmrtPackManifest> = coroutineScope {
                val summary  = async(Dispatchers.IO) { client.fetchSummary(packId) }
                val manifest = async(Dispatchers.IO) { client.fetchManifest(packId) }
                awaitAll(summary, manifest)
                summary.await() to manifest.await()
            }
            DetailState.Loaded(pair.first, pair.second)
        } catch (e: Exception) {
            DetailState.Error(e.message ?: s.browseDetailErrorMessage)
        }
    }

    var selectedOptionalMods   by remember(packId) { mutableStateOf(emptySet<String>()) }
    var selectedOptionalAssets by remember(packId) { mutableStateOf(emptySet<String>()) }
    var modDialogOpen          by remember { mutableStateOf(false) }
    var assetDialogOpen        by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hero(packId = packId, summary = (state as? DetailState.Loaded)?.summary, onBack = onBack)

            when (val st = state) {
                DetailState.Loading -> Box(
                    Modifier.fillMaxWidth().height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color       = CelestiaTheme.colors.primary.copy(alpha = 0.6f),
                        strokeWidth = 2.dp,
                        modifier    = Modifier.size(28.dp),
                    )
                }
                is DetailState.Error -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text       = s.browseDetailErrorTitle,
                            style      = MaterialTheme.typography.titleLarge,
                            color      = CelestiaTheme.colors.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text      = st.message,
                            style     = MaterialTheme.typography.bodySmall,
                            color     = CelestiaTheme.colors.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.widthIn(max = 480.dp),
                        )
                        Button(onClick = { retryTick++ }) { Text(s.browseRetry) }
                    }
                }
                is DetailState.Loaded -> LoadedBody(
                    summary             = st.summary,
                    manifest            = st.manifest,
                    onOpenModBrowser    = { modDialogOpen = true },
                    onOpenAssetBrowser  = { assetDialogOpen = true },
                )
            }
        }

        // Floating browsers -- overlay above the scrolling content,
        // backdrop dismisses, selection state lives at this screen
        // level so re-opens preserve what the user already picked.
        val loaded = state as? DetailState.Loaded
        if (modDialogOpen && loaded != null) {
            ModBrowserDialog(
                mods             = loaded.manifest.mods,
                initialSelection = selectedOptionalMods,
                onApply          = { selectedOptionalMods = it },
                onDismiss        = { modDialogOpen = false },
            )
        }
        if (assetDialogOpen && loaded != null) {
            AssetBrowserDialog(
                assets           = loaded.manifest.assets,
                initialSelection = selectedOptionalAssets,
                onApply          = { selectedOptionalAssets = it },
                onDismiss        = { assetDialogOpen = false },
            )
        }
    }
}

@Composable
private fun Hero(packId: String, summary: SmrtPackSummary?, onBack: () -> Unit) {
    val gradient = Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF1D4ED8)))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(gradient),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        IconButton(
            onClick  = onBack,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
        }

        Column(
            modifier            = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text       = summary?.displayName ?: packId,
                style      = MaterialTheme.typography.headlineLarge,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (summary != null && summary.tagline.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = summary.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun LoadedBody(
    summary: SmrtPackSummary,
    manifest: SmrtPackManifest,
    onOpenModBrowser: () -> Unit,
    onOpenAssetBrowser: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier            = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Chip("MC ${manifest.minecraft.version}")
            Chip("${manifest.loader.name} ${manifest.loader.version}")
            Chip("Java ${manifest.java.major}")
            Chip(manifest.packVersion, emphasis = true)
        }

        Section(title = s.browseDetailInstallTitle) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = s.browseDetailInstallReady,
                        style      = MaterialTheme.typography.titleMedium,
                        color      = CelestiaTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text  = s.browseDetailInstallHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
                Button(
                    onClick = { /* TODO: install pipeline -- next PR */ },
                    enabled = false,
                    shape   = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = CelestiaTheme.colors.primary,
                        contentColor   = Color.White,
                    ),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(s.browseDetailInstallButton, fontWeight = FontWeight.Bold)
                }
            }
        }

        ModsSection(mods = manifest.mods, onOpenBrowser = onOpenModBrowser)
        AssetsSection(assets = manifest.assets, onOpenBrowser = onOpenAssetBrowser)

        if (summary.tags.isNotEmpty()) {
            Section(title = s.browseDetailTagsTitle) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.tags.forEach { Chip(it) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text       = title.uppercase(),
            style      = MaterialTheme.typography.titleSmall,
            color      = CelestiaTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(glassSurfaceAlpha(0.6f))
                .padding(16.dp),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun Chip(text: String, emphasis: Boolean = false) {
    AssistChip(
        onClick = {},
        enabled = false,
        shape   = RoundedCornerShape(8.dp),
        label   = { Text(text, style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textPrimary) },
        colors  = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (emphasis) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
                                     else          glassSurfaceAlpha(0.4f),
            disabledLabelColor     = CelestiaTheme.colors.textPrimary,
        ),
        border  = null,
    )
}

// ─── Mods section ─────────────────────────────────────────────────────────────

@Composable
private fun ModsSection(mods: List<SmrtModEntry>, onOpenBrowser: () -> Unit) {
    val s = LocalStrings.current
    var query    by remember { mutableStateOf("") }
    val expanded = remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(mods, query) {
        if (query.isBlank()) mods
        else {
            val q = query.trim().lowercase()
            mods.filter { mod ->
                mod.filename.lowercase().contains(q)
                    || (mod.display?.name?.lowercase()?.contains(q) == true)
                    || (mod.display?.category?.lowercase()?.contains(q) == true)
            }
        }
    }

    Section(title = s.browseDetailModsTitle) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onOpenBrowser,
                shape   = RoundedCornerShape(8.dp),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) { Text(s.modBrowserOpenButton, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value         = query,
            onValueChange = { query = it },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(s.browseDetailModsSearch, style = MaterialTheme.typography.bodySmall) },
            leadingIcon   = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = CelestiaTheme.colors.textSecondary)
            },
            shape         = RoundedCornerShape(10.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedTextColor        = CelestiaTheme.colors.textPrimary,
                unfocusedTextColor      = CelestiaTheme.colors.textPrimary,
                focusedBorderColor      = CelestiaTheme.colors.primary,
                unfocusedBorderColor    = CelestiaTheme.colors.outline.copy(alpha = 0.4f),
                cursorColor             = CelestiaTheme.colors.primary,
                focusedPlaceholderColor   = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                unfocusedPlaceholderColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text  = s.browseDetailModsFilteredCount.format(filtered.size, mods.size),
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Text(
                text  = s.browseDetailModsEmptyFilter,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filtered.forEach { mod ->
                    ModRow(
                        mod        = mod,
                        isExpanded = mod.filename in expanded.value,
                        onToggle   = {
                            expanded.value = if (mod.filename in expanded.value) {
                                expanded.value - mod.filename
                            } else {
                                expanded.value + mod.filename
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModRow(mod: SmrtModEntry, isExpanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(glassSurfaceAlpha(0.35f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = mod.display?.name?.takeIf { it.isNotBlank() } ?: mod.filename,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                )
                if (mod.display?.name?.takeIf { it.isNotBlank() } != null) {
                    Text(
                        text  = mod.filename,
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector        = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint               = CelestiaTheme.colors.textSecondary,
                modifier           = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallChip(formatBytes(mod.sizeBytes))
            SourceChip(mod.source)
            if (!mod.required) SmallChip(LocalStrings.current.browseDetailModsOptional)
            mod.display?.category?.takeIf { it.isNotBlank() }?.let { SmallChip(it) }
            mod.display?.license?.takeIf { it.isNotBlank() }?.let { SmallChip(it) }
        }
        if (isExpanded) {
            ModExpanded(mod)
        }
    }
}

@Composable
private fun ModExpanded(mod: SmrtModEntry) {
    val s = LocalStrings.current
    Spacer(Modifier.height(8.dp))
    mod.display?.description?.takeIf { it.isNotBlank() }?.let {
        Text(
            text  = it,
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(6.dp))
    }
    if (mod.display?.incompatibleWith?.isNotEmpty() == true) {
        Text(
            text  = s.browseDetailModsIncompatible.format(mod.display!!.incompatibleWith.joinToString(", ")),
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.error.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(6.dp))
    }
    mod.display?.url?.takeIf { it.isNotBlank() }?.let { url ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier
                .clickable { SystemActions.openUrl(url) }
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint               = CelestiaTheme.colors.primary,
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = url,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.primary,
            )
        }
    }
}

// ─── Assets section ───────────────────────────────────────────────────────────

@Composable
private fun AssetsSection(assets: List<SmrtAssetEntry>, onOpenBrowser: () -> Unit) {
    val s = LocalStrings.current
    Section(title = s.browseDetailAssetsTitle) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onOpenBrowser,
                enabled = assets.isNotEmpty(),
                shape   = RoundedCornerShape(8.dp),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) { Text(s.assetBrowserOpenButton, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(8.dp))
        if (assets.isEmpty()) {
            Text(
                text  = s.browseDetailAssetsEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
            )
        } else {
            Text(
                text  = s.browseDetailAssetsCount.format(assets.size),
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                assets.forEach { asset -> AssetRow(asset) }
            }
        }
    }
}

@Composable
private fun AssetRow(asset: SmrtAssetEntry) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(glassSurfaceAlpha(0.3f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text     = asset.dest,
            style    = MaterialTheme.typography.bodySmall,
            color    = CelestiaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        SmallChip(formatBytes(asset.sizeBytes))
        SourceChip(asset.source)
    }
}

// ─── Shared chips + helpers ───────────────────────────────────────────────────

@Composable
private fun SmallChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(glassSurfaceAlpha(0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary)
    }
}

@Composable
private fun SourceChip(source: SmrtSource) {
    val s = LocalStrings.current
    val (label, color) = when (source) {
        is SmrtSource.Modrinth   -> s.browseDetailSourceModrinth     to Color(0xFF22C55E)
        is SmrtSource.SmrtCache  -> s.browseDetailSourceMirrorCache  to Color(0xFF3B82F6)
        is SmrtSource.SmrtStatic -> s.browseDetailSourceMirrorStatic to Color(0xFF8B5CF6)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024) return "%.1f KB".format(Locale.ROOT, kb)
    val mb = kb / 1_024.0
    if (mb < 1_024) return "%.1f MB".format(Locale.ROOT, mb)
    val gb = mb / 1_024.0
    return "%.2f GB".format(Locale.ROOT, gb)
}

private sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val summary: SmrtPackSummary, val manifest: SmrtPackManifest) : DetailState()
    data class Error(val message: String) : DetailState()
}
