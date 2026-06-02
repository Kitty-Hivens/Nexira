package hivens.ui.screens.library.content

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.data.ContentToggle
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.launch.LauncherController
import hivens.launcher.smrt.DepGraph
import hivens.launcher.smrt.DepGraphResolver
import hivens.launcher.smrt.ModGrouping
import hivens.launcher.smrt.ModRoleGrouper
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.i18n.LocalStrings
import hivens.ui.screens.RetryStateBlock
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
    val controller: LauncherController = koinInject()
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
            modifier   = modifier,
            instance   = instance,
            controller = controller,
            manifest   = st.manifest,
            graph      = st.graph,
            grouping   = st.grouping,
        )
    }
}

@Composable
private fun ErrorBlock(modifier: Modifier, message: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    RetryStateBlock(
        title      = s.contentTabFetchErrorTitle,
        message    = message,
        retryLabel = s.contentTabRetry,
        onRetry    = onRetry,
        modifier   = modifier.fillMaxSize().padding(32.dp),
        titleStyle = MaterialTheme.typography.titleMedium,
        spacing    = 10.dp,
    )
}

@Composable
private fun LoadedBody(
    modifier: Modifier,
    instance: PackInstance,
    controller: LauncherController,
    manifest: SmrtPackManifest,
    graph: DepGraph,
    grouping: ModGrouping,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()

    val optionalMods = OptionalContentRules.optionalMods(manifest.mods)
    // Key on instance.id, not manifest.packId: two installed instances of the
    // same pack reuse this composable but carry independent optionalContent;
    // keying on the pack id would leak the previous instance's checkbox state
    // into the second instance and persist it on the next click.
    var enabledState by remember(instance.id) {
        mutableStateOf(OptionalContentRules.enabledState(manifest.mods, instance.optionalContent))
    }
    val onToggleOptional: (String, Boolean) -> Unit = { filename, enable ->
        val next = OptionalContentRules.applyToggle(manifest.mods, enabledState, filename, enable)
        enabledState = next
        val toggles = optionalMods.map { ContentToggle(it.filename, next[it.filename] ?: it.defaultEnabled) }
        // Hand the write to the controller's long-lived scope. Doing this on a
        // rememberCoroutineScope() one cancelled mid-flight when the user
        // navigated away from the Content tab, so the toggle silently reverted.
        controller.setOptionalModsAsync(instance, manifest, toggles)
    }
    // Leading checkbox per mod row: required mods are locked-on (checked +
    // disabled, kept for column alignment), optional mods toggle here.
    fun toggleFor(mod: SmrtModEntry): ModToggle =
        if (mod.required) {
            ModToggle(checked = true, locked = true, onToggle = {})
        } else {
            ModToggle(
                checked = enabledState[mod.filename] ?: mod.defaultEnabled,
                locked = false,
                onToggle = { enable -> onToggleOptional(mod.filename, enable) },
            )
        }

    // Split ungrouped mods so libraries land in their own bucket.
    // Mirror tags lib-only mods with display.category=lib (8 of 90 on
    // Industrial today), and surfacing them inline pollutes the main
    // list with rows the user doesn't care about for inspection.
    val (libs, regularMods) = grouping.ungrouped.partition { it.libraryLike() }

    // Split assets by dest-prefix. Config dir has 100+ entries on
    // Industrial; the prefix groupings give the user a cleanly
    // collapsible breakdown (resourcepacks / shaderpacks always open,
    // configs collapsed by default since their volume drowns the rest).
    val resourcePacks = manifest.assets.filter { it.dest.startsWith("resourcepacks/") }
    val shaderPacks   = manifest.assets.filter { it.dest.startsWith("shaderpacks/") }
    val configs       = manifest.assets.filter { it.dest.startsWith("config/") }
    val otherAssets   = manifest.assets.filter { a ->
        !a.dest.startsWith("resourcepacks/") &&
        !a.dest.startsWith("shaderpacks/") &&
        !a.dest.startsWith("config/")
    }

    // Per-section open state. Keyed on pack id so a swap to a different
    // PackDetail resets to defaults.
    var libsOpen          by remember(manifest.packId) { mutableStateOf(false) }
    var resourcePacksOpen by remember(manifest.packId) { mutableStateOf(true) }
    var shaderPacksOpen   by remember(manifest.packId) { mutableStateOf(true) }
    var configsOpen       by remember(manifest.packId) { mutableStateOf(false) }
    var otherAssetsOpen   by remember(manifest.packId) { mutableStateOf(true) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state               = listState,
            modifier            = Modifier.fillMaxSize().padding(start = 20.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

            if (regularMods.isNotEmpty()) {
                item { SectionHeader(text = s.contentTabModsSection(regularMods.size)) }
                items(items = regularMods, key = { it.filename }) { mod ->
                    ModRowPanel(mod = mod, graph = graph, toggle = toggleFor(mod))
                }
            }

            collapsibleModSection(
                title = s.contentTabLibrariesSection(libs.size),
                mods  = libs,
                graph = graph,
                isOpen = libsOpen,
                onToggle = { libsOpen = !libsOpen },
                toggleFor = ::toggleFor,
            )
            collapsibleAssetSection(
                title    = s.contentTabResourcePacksSection(resourcePacks.size),
                assets   = resourcePacks,
                isOpen   = resourcePacksOpen,
                onToggle = { resourcePacksOpen = !resourcePacksOpen },
            )
            collapsibleAssetSection(
                title    = s.contentTabShaderPacksSection(shaderPacks.size),
                assets   = shaderPacks,
                isOpen   = shaderPacksOpen,
                onToggle = { shaderPacksOpen = !shaderPacksOpen },
            )
            collapsibleAssetSection(
                title    = s.contentTabConfigsSection(configs.size),
                assets   = configs,
                isOpen   = configsOpen,
                onToggle = { configsOpen = !configsOpen },
            )
            collapsibleAssetSection(
                title    = s.contentTabOtherAssetsSection(otherAssets.size),
                assets   = otherAssets,
                isOpen   = otherAssetsOpen,
                onToggle = { otherAssetsOpen = !otherAssetsOpen },
            )

            item { Spacer(Modifier.height(8.dp)) }
        }
        VerticalScrollbar(
            adapter  = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

private fun SmrtModEntry.libraryLike(): Boolean =
    display?.category?.lowercase()?.let { it == "lib" || it == "library" } == true

private fun LazyListScope.collapsibleModSection(
    title: String,
    mods: List<SmrtModEntry>,
    graph: DepGraph,
    isOpen: Boolean,
    onToggle: () -> Unit,
    toggleFor: (SmrtModEntry) -> ModToggle,
) {
    if (mods.isEmpty()) return
    item { CollapsibleSectionHeader(text = title, isOpen = isOpen, onToggle = onToggle) }
    if (isOpen) {
        items(items = mods, key = { "mod:${it.filename}" }) { mod ->
            ModRowPanel(mod = mod, graph = graph, toggle = toggleFor(mod))
        }
    }
}

private fun LazyListScope.collapsibleAssetSection(
    title: String,
    assets: List<SmrtAssetEntry>,
    isOpen: Boolean,
    onToggle: () -> Unit,
) {
    if (assets.isEmpty()) return
    item { CollapsibleSectionHeader(text = title, isOpen = isOpen, onToggle = onToggle) }
    if (isOpen) {
        items(items = assets, key = { "asset:${it.dest}" }) { asset ->
            AssetRowPanel(asset = asset)
        }
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
private fun CollapsibleSectionHeader(text: String, isOpen: Boolean, onToggle: () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector        = if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint               = CelestiaTheme.colors.primary,
            modifier           = Modifier.size(18.dp),
        )
        Text(
            text       = text,
            style      = MaterialTheme.typography.titleSmall,
            color      = CelestiaTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
    }
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
