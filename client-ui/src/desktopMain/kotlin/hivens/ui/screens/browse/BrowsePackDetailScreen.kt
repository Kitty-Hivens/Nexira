package hivens.ui.screens.browse

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

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
            is DetailState.Loaded -> LoadedBody(summary = st.summary, manifest = st.manifest)
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
private fun LoadedBody(summary: SmrtPackSummary, manifest: SmrtPackManifest) {
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

        Section(title = s.browseDetailModsTitle) {
            Text(
                text       = s.browseDetailModsCount.format(manifest.mods.size),
                style      = MaterialTheme.typography.bodyMedium,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            manifest.mods.take(6).forEach { mod ->
                Text(
                    text  = mod.filename,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
            if (manifest.mods.size > 6) {
                Text(
                    text  = s.browseDetailModsMore.format(manifest.mods.size - 6),
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                )
            }
        }

        Section(title = s.browseDetailAssetsTitle) {
            Text(
                text  = s.browseDetailAssetsCount.format(manifest.assets.size),
                style = MaterialTheme.typography.bodyMedium,
                color = CelestiaTheme.colors.textPrimary,
            )
        }

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

private sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val summary: SmrtPackSummary, val manifest: SmrtPackManifest) : DetailState()
    data class Error(val message: String) : DetailState()
}
