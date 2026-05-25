package hivens.ui.screens.library.content

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.smrt.DepGraph
import hivens.launcher.smrt.DepGraphResolver
import hivens.launcher.smrt.ModGrouping
import hivens.launcher.smrt.ModRoleGrouper
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Top-level Content tab for the Library PackDetail screen. Fetches
 * the mirror manifest for [instance], runs DAG + role resolvers, then
 * renders three stacked sections: role groups, ungrouped mods,
 * assets. Each row is independently expandable.
 *
 * Today the manifest fetch is always remote (one /v1/packs/{id}/manifest
 * call per tab open); a future iteration can read a locally-cached
 * manifest from the install dir to avoid the round-trip.
 *
 * Currently supports [PackOrigin.Mirror] packs only -- SC packs don't
 * carry a smrt manifest and Local / Modrinth pack-builder isn't wired
 * yet. Other origins get a polite "not available" placeholder.
 */
@Composable
fun ContentTabPane(instance: PackInstance, modifier: Modifier = Modifier) {
    val s = LocalStrings.current

    if (instance.packRef.origin != PackOrigin.Mirror) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text      = s.contentTabUnsupportedOrigin,
                style     = MaterialTheme.typography.bodyMedium,
                color     = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val client: SmrtPackClient = koinInject()
    var state by remember(instance.id) { mutableStateOf<ContentState>(ContentState.Loading) }
    var retryTick by remember(instance.id) { mutableIntStateOf(0) }

    LaunchedEffect(instance.id, retryTick) {
        state = ContentState.Loading
        state = try {
            val manifest = withContext(Dispatchers.IO) { client.fetchManifest(instance.packRef.id) }
            val graph    = DepGraphResolver.resolve(manifest)
            val grouping = ModRoleGrouper.group(manifest.mods)
            ContentState.Loaded(manifest = manifest, graph = graph, grouping = grouping)
        } catch (e: Exception) {
            ContentState.Error(message = e.message ?: s.contentTabFetchErrorGeneric)
        }
    }

    when (val st = state) {
        ContentState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color       = CelestiaTheme.colors.primary.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier    = Modifier.size(28.dp),
            )
        }
        is ContentState.Error -> ErrorBlock(modifier = modifier, message = st.message, onRetry = { retryTick++ })
        is ContentState.Loaded -> LoadedBody(
            modifier = modifier,
            manifest = st.manifest,
            graph    = st.graph,
            grouping = st.grouping,
        )
    }
}

@Composable
private fun ErrorBlock(modifier: Modifier, message: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text       = s.contentTabFetchErrorTitle,
                style      = MaterialTheme.typography.titleMedium,
                color      = CelestiaTheme.colors.error,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = message,
                style     = MaterialTheme.typography.bodySmall,
                color     = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 480.dp),
            )
            Button(
                onClick = onRetry,
                shape   = RoundedCornerShape(10.dp),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) { Text(s.contentTabRetry, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun LoadedBody(
    modifier: Modifier,
    manifest: SmrtPackManifest,
    graph: DepGraph,
    grouping: ModGrouping,
) {
    val s = LocalStrings.current

    LazyColumn(
        modifier            = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (graph.cycles.isNotEmpty() || graph.missingRequirements.isNotEmpty()) {
            item { ResolverWarnings(graph = graph) }
        }

        if (grouping.byRole.isNotEmpty()) {
            item { SectionHeader(text = s.contentTabRoleSection) }
            items(items = grouping.byRole, key = { it.role }) { group ->
                RoleGroupSection(group = group, graph = graph)
            }
        }

        if (grouping.ungrouped.isNotEmpty()) {
            item {
                SectionHeader(text = s.contentTabModsSection(grouping.ungrouped.size))
            }
            items(items = grouping.ungrouped, key = { it.filename }) { mod ->
                ModRowPanel(mod = mod, graph = graph)
            }
        }

        if (manifest.assets.isNotEmpty()) {
            item { SectionHeader(text = s.contentTabAssetsSection(manifest.assets.size)) }
            items(items = manifest.assets, key = { it.dest }) { asset ->
                AssetRowPanel(asset = asset)
            }
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
private fun ResolverWarnings(graph: DepGraph) {
    val s = LocalStrings.current
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CelestiaTheme.colors.error.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text       = s.contentTabResolverIssuesTitle,
            style      = MaterialTheme.typography.titleSmall,
            color      = CelestiaTheme.colors.error,
            fontWeight = FontWeight.Bold,
        )
        if (graph.missingRequirements.isNotEmpty()) {
            Text(
                text  = s.contentTabResolverMissing(graph.missingRequirements.size),
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textPrimary,
            )
        }
        if (graph.cycles.isNotEmpty()) {
            Text(
                text  = s.contentTabResolverCycles(graph.cycles.size),
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textPrimary,
            )
        }
    }
}

private sealed class ContentState {
    object Loading : ContentState()
    data class Loaded(
        val manifest: SmrtPackManifest,
        val graph: DepGraph,
        val grouping: ModGrouping,
    ) : ContentState()
    data class Error(val message: String) : ContentState()
}
