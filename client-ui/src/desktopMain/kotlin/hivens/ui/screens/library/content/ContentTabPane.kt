package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.modrinth.ModrinthClient
import hivens.core.data.PackInstance
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentScanner
import androidx.compose.runtime.DisposableEffect
import hivens.ui.activity.Selection
import hivens.ui.activity.dragSelect
import hivens.ui.activity.SelectionAction
import hivens.ui.activity.SelectionActionKind
import hivens.ui.activity.SelectionItem
import hivens.ui.activity.SelectionRegistry
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxSwitch
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import hivens.ui.utils.humanSize
import hivens.ui.utils.rememberFileDialogSettings
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Library PackDetail Content tab. Reads what is ACTUALLY installed under the
 * instance (its `mods/`, `resourcepacks/`, `shaderpacks/` folders) via
 * [InstanceContentScanner] -- origin-agnostic, so it works for mirror, Modrinth
 * and from-scratch packs alike. Display is always on; managing (toggle / delete)
 * is unlocked once the instance is detached from its pack ([PackOrigin.Local]),
 * matching "detach, then make it your own". Detaching itself lives in the pack's
 * Data settings, not here: it costs the instance its update source, so it must not
 * sit one click away on a browsing surface.
 *
 * The scan, the pack manifest, the caches and every mutation live in
 * [ContentTabState]; this renders that state and forwards intents.
 */
@Composable
fun ContentTabPane(instance: PackInstance, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val state = rememberContentTabState(instance)
    val selections: SelectionRegistry = koinInject()
    val addDialogSettings = rememberFileDialogSettings(s.contentAddFiles)

    LaunchedEffect(state) { state.load() }
    // Separate from load(): this one runs for as long as the tab is on screen,
    // picking up anything added to the folders from outside the launcher.
    LaunchedEffect(state) { state.watchContentFolders() }

    // The selection lives on the activity surface, so this view publishes it and
    // takes it back down on the way out. Leaving the tab with rows still ticked
    // would otherwise leave a bar offering to delete things the user can no
    // longer see.
    val picked = state.picked
    val blocked = if (state.lockedCount > 0) s.selectionBlockedByPack(state.lockedCount) else null
    DisposableEffect(picked, blocked) {
        val published = if (picked.isEmpty()) null else Selection(
            items = picked.map {
                SelectionItem(it.selectionKey(), it.displayName, state.iconFor(it)?.model())
            },
            actions = listOf(
                SelectionAction(SelectionActionKind.Enable, blockedReason = blocked) {
                    state.setEnabledForSelection(picked, true)
                },
                SelectionAction(SelectionActionKind.Disable, blockedReason = blocked) {
                    state.setEnabledForSelection(picked, false)
                },
                SelectionAction(SelectionActionKind.Delete, blockedReason = blocked) { state.requestBulkDelete() },
            ),
            clear = { state.clearSelection() },
        )
        selections.set(published)
        onDispose { selections.clearIf(published) }
    }

    if (state.browsing) {
        ModBrowser(
            mcVersion = instance.cachedManifest?.minecraftVersion.orEmpty(),
            loader    = instance.cachedManifest?.loaderName
                ?.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }
                ?.lowercase().orEmpty(),
            modsDir   = state.instanceDir.resolve("mods"),
            modifier  = modifier,
            onBack    = state::stopBrowsing,
        )
        return
    }

    Column(
        modifier            = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toolbar(
            query          = state.query,
            onQuery        = { state.query = it },
            filter         = state.filter,
            onFilter       = { state.filter = it },
            // Adding mods is gated behind detach; resource / shader packs can be added
            // any time (switch to their filter to target that folder). "Find projects"
            // is the Modrinth MOD browser, so it stays mod-gated.
            canAdd         = state.isLocal ||
                state.filter.kind == ContentKind.ResourcePack ||
                state.filter.kind == ContentKind.ShaderPack,
            canFindProjects = state.isLocal,
            onAddFiles     = { state.addFiles(addDialogSettings) },
            onFindProjects = state::startBrowsing,
        )

        val visible = state.visible
        when {
            state.items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NxTheme.colors.primary.copy(alpha = 0.6f), strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.contentEmpty, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
            }
            else -> {
                val listState = rememberLazyListState()
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Box(Modifier.fillMaxSize().hoverable(hover)) {
                    LazyColumn(
                        state               = listState,
                        modifier            = Modifier.fillMaxSize().dragSelect(
                            listState   = listState,
                            keyAt       = { index -> visible.getOrNull(index)?.selectionKey() },
                            isSelected  = { it in state.selectedKeys },
                            setSelected = state::setSelected,
                            selecting   = state.selectedKeys.isNotEmpty(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = visible, key = { it.selectionKey() }) { c ->
                            val rules = state.rulesFor(c)
                            ContentRow(
                                content        = c,
                                selected       = c.selectionKey() in state.selectedKeys,
                                iconState      = state.iconFor(c),
                                rules          = rules,
                                onToggle       = { enabled -> state.toggle(c, enabled) },
                                onDelete       = if (rules.canDelete) ({ state.requestDelete(c) }) else null,
                                onDetails      = { state.detailsOf = c },
                                resolveProject = { state.resolveProject(c) },
                            )
                        }
                    }
                    NxVerticalScrollbar(
                        adapter  = rememberScrollbarAdapter(listState),
                        revealed = hovered || listState.isScrollInProgress,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    if (state.pendingBulkDelete.isNotEmpty()) {
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentBulkDeleteBody(state.pendingBulkDelete.size),
            confirmLabel = s.editorDelete,
            onConfirm    = state::confirmDelete,
            onDismiss    = state::cancelDelete,
        )
    }

    if (state.pendingDelete != null) {
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentDeleteBody,
            confirmLabel = s.editorDelete,
            onConfirm    = state::confirmDelete,
            onDismiss    = state::cancelDelete,
        )
    }

    state.detailsOf?.let { target ->
        ContentDetailsDialog(
            content        = target,
            resolveProject = { state.resolveProject(target) },
            onDismiss      = { state.detailsOf = null },
        )
    }
}

@Composable
private fun Toolbar(
    query: String,
    onQuery: (String) -> Unit,
    filter: ContentFilter,
    onFilter: (ContentFilter) -> Unit,
    canAdd: Boolean,
    canFindProjects: Boolean,
    onAddFiles: () -> Unit,
    onFindProjects: () -> Unit,
) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentSearch(query, onQuery, s.contentSearchPlaceholder)
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            FilterChip(s.contentFilterAll, filter == ContentFilter.All) { onFilter(ContentFilter.All) }
            FilterChip(s.contentFilterMods, filter == ContentFilter.Mods) { onFilter(ContentFilter.Mods) }
            FilterChip(s.contentFilterResourcePacks, filter == ContentFilter.ResourcePacks) { onFilter(ContentFilter.ResourcePacks) }
            FilterChip(s.contentFilterShaderPacks, filter == ContentFilter.ShaderPacks) { onFilter(ContentFilter.ShaderPacks) }
            Spacer(Modifier.weight(1f))
            if (canFindProjects) {
                NxButton(label = s.contentFindProjects, onClick = onFindProjects, style = NxButtonStyle.Secondary, icon = NxIcon.Search, compact = true)
            }
            if (canAdd) {
                NxButton(label = s.contentAddFiles, onClick = onAddFiles, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
            }
        }
    }
}

@Composable
private fun ContentSearch(query: String, onQuery: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value         = query,
        onValueChange = onQuery,
        singleLine    = true,
        textStyle     = MaterialTheme.typography.bodyMedium.copy(color = NxTheme.colors.textPrimary),
        cursorBrush   = SolidColor(NxTheme.colors.primary),
        modifier      = Modifier.fillMaxWidth(),
    ) { inner ->
        Row(
            modifier          = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(NxTheme.colors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(NxIcon.Search, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
                inner()
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) NxTheme.colors.primary else glassSurfaceAlpha(0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            color      = if (selected) Color.White else NxTheme.colors.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * One content row, drawn from the [rules] the state computed for it: what the
 * switch reads, whether there is one at all, and whether the row may be deleted.
 * [onDelete] is null unless the row is user-owned.
 */
@Composable
private fun ContentRow(
    content: InstalledContent,
    iconState: ContentIconState?,
    rules: ContentRowRules,
    // Every row selects. A control that appears on some rows and not others
    // ragged the left edge of the whole list and told the user the difference
    // before they had asked a question it answers; what a row can have DONE to it
    // is a property of the action, not of whether it may be pointed at.
    //
    // The gesture itself belongs to the list: a drag is followed across children,
    // and a row only ever hears about itself.
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDetails: () -> Unit,
    resolveProject: suspend () -> ModrinthProject?,
) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    val dim = if (rules.effectiveEnabled) 1f else 0.5f
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) NxTheme.colors.primary.copy(alpha = 0.14f)
                else glassSurfaceAlpha(0.4f),
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The tick rides the icon rather than taking a column of its own, which
        // is what keeps every row the same shape whether or not anything is
        // selected. Messaging apps settled on this for the same reason.
        Box {
            ContentIcon(iconState, content.fileName, content.displayName, dim)
            if (selected) {
                Box(
                    Modifier.matchParentSize().clip(MaterialTheme.shapes.small)
                        .background(NxTheme.colors.primary.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(NxIcon.Check, contentDescription = null, tint = NxTheme.colors.onPrimary, size = 16.dp)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text       = content.displayName,
                style      = MaterialTheme.typography.bodyMedium,
                color      = NxTheme.colors.textPrimary.copy(alpha = dim),
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            content.version?.let { v ->
                Text(v, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary.copy(alpha = dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rules.showToggle) {
            NxSwitch(
                checked         = rules.effectiveEnabled,
                onCheckedChange = onToggle,
            )
        }
        // One overflow instead of a bare trash can: Details is always available
        // (local metadata at minimum); Open page and Delete appear only when the
        // caller passed them (a mod with a known URL / a user-owned row).
        NxKebabButton(contentDescription = s.packCardMore) { dismiss ->
            NxMenuItem(label = s.contentActionDetails, icon = NxIcon.Info, onClick = { dismiss(); onDetails() })
            // "Open page" is kind-agnostic: the embedded homepage if the archive
            // declared one, else the canonical Modrinth page (mod / resourcepack /
            // shader all resolve by file hash). Resolved while the menu is open, so
            // it appears once a URL is known and never sits there dead for content
            // with no page anywhere.
            var page by remember(content.fileName) { mutableStateOf(content.homepageUrl) }
            if (page == null) {
                LaunchedEffect(content.fileName) {
                    page = resolveProject()?.let { "https://modrinth.com/${it.projectType}/${it.slug}" }
                }
            }
            page?.let { url ->
                NxMenuItem(label = s.contentActionOpenPage, icon = NxIcon.OpenInNew, onClick = { dismiss(); uriHandler.openUri(url) })
            }
            if (onDelete != null) {
                NxMenuItem(label = s.editorDelete, icon = NxIcon.Delete, destructive = true, onClick = { dismiss(); onDelete() })
            }
        }
    }
}

/**
 * Pure icon renderer: draws the pre-resolved [state] that the panel's prefetch put in
 * the shared cache, so a row does no resolution of its own. `null` = still resolving
 * (a tinted placeholder); [ContentIconState.None] = nothing found anywhere (a letter).
 * Resolution order lives in the prefetch: embedded -> Modrinth-by-hash -> jar probe.
 */
@Composable
private fun ContentIcon(state: ContentIconState?, seed: String, displayName: String, dim: Float) {
    val box = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
    when (state) {
        is ContentIconState.Bytes -> AsyncImage(model = state.data, contentDescription = null, contentScale = ContentScale.Crop, modifier = box)
        is ContentIconState.Url   -> AsyncImage(model = state.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = box)
        ContentIconState.None     -> Box(
            modifier         = box.background(NxTheme.colors.decorativeColor(seed).copy(alpha = dim)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = displayName.firstOrNull()?.uppercase() ?: "?",
                style      = MaterialTheme.typography.labelMedium,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )
        }
        // Still resolving: tinted box, same tint as the letter, so settling doesn't flash.
        null -> Box(box.background(NxTheme.colors.decorativeColor(seed).copy(alpha = dim)))
    }
}

/**
 * "Find projects" browser: searches Modrinth for MODS compatible with the
 * instance's MC + loader and downloads the best-matching version straight into
 * `mods/`. Reachable only from an editable (detached) instance.
 */
@Composable
private fun ModBrowser(mcVersion: String, loader: String, modsDir: Path, modifier: Modifier, onBack: () -> Unit) {
    val s = LocalStrings.current
    val modrinth: ModrinthClient = koinInject()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModrinthSearchHit>?>(null) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(emptySet<String>()) }

    // Debounce typing, then search on the settled query.
    LaunchedEffect(query) { delay(350); submitted = query }
    LaunchedEffect(submitted, mcVersion, loader) {
        results = null
        results = runCatching { withContext(Dispatchers.IO) { modrinth.searchMods(submitted, mcVersion, loader).hits } }.getOrDefault(emptyList())
    }

    Column(
        modifier            = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(6.dp)) {
                Symbol(NxIcon.ArrowBack, contentDescription = null, tint = NxTheme.colors.textPrimary, size = 20.dp)
            }
            Text(s.contentFindProjects, style = MaterialTheme.typography.titleMedium, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        }
        ContentSearch(query, { query = it }, s.contentSearchPlaceholder)

        val r = results
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NxTheme.colors.primary.copy(alpha = 0.6f), strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            r.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.contentEmpty, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
            }
            else -> {
                val listState = rememberLazyListState()
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Box(Modifier.fillMaxSize().hoverable(hover)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items = r, key = { it.projectId }) { hit ->
                            ModResultRow(
                                hit       = hit,
                                installed = hit.projectId in installed,
                                working   = hit.projectId in working,
                                onInstall = {
                                    working = working + hit.projectId
                                    scope.launch {
                                        runCatching {
                                            modrinth.bestModVersion(hit.projectId, mcVersion, loader)?.let { v ->
                                                val f = v.primaryFile()
                                                modrinth.downloadTo(f.url, modsDir.resolve(f.filename))
                                            }
                                        }
                                        working = working - hit.projectId
                                        installed = installed + hit.projectId
                                    }
                                },
                            )
                        }
                    }
                    NxVerticalScrollbar(adapter = rememberScrollbarAdapter(listState), revealed = hovered || listState.isScrollInProgress, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ModResultRow(hit: ModrinthSearchHit, installed: Boolean, working: Boolean, onInstall: () -> Unit) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier              = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(glassSurfaceAlpha(0.4f)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hit.iconUrl != null) {
            AsyncImage(model = hit.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(shape))
        } else {
            Box(Modifier.size(36.dp).clip(shape).background(NxTheme.colors.decorativeColor(hit.title)), contentAlignment = Alignment.Center) {
                Text(hit.title.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.description.isNotBlank()) {
                Text(hit.description, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            installed -> Symbol(NxIcon.Check, contentDescription = null, tint = NxTheme.colors.primary, size = 20.dp)
            working   -> CircularProgressIndicator(color = NxTheme.colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            else      -> NxButton(label = s.browseDetailInstallButton, onClick = onInstall)
        }
    }
}

/**
 * Read-only details for one installed item. Everything but the Modrinth link is
 * offline (the jar / pack declared it). [pageUrl] starts at the embedded homepage
 * and, for a mod without one, best-effort resolves the canonical Modrinth page by
 * file hash -- a non-Modrinth / private jar simply keeps a null link.
 */
@Composable
private fun ContentDetailsDialog(
    content: InstalledContent,
    resolveProject: suspend () -> ModrinthProject?,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    // The archive's own metadata is authoritative; the Modrinth project (resolved
    // by file hash, any kind) only fills the gaps a sparse archive leaves -- so a
    // resource pack from Modrinth reads like a mod from Modrinth.
    var project by remember(content.fileName) { mutableStateOf<ModrinthProject?>(null) }
    LaunchedEffect(content.fileName) { project = resolveProject() }
    val description = content.description ?: project?.description?.takeIf { it.isNotBlank() }
    val license = content.license ?: project?.license?.let { it.name ?: it.id }
    val pageUrl = content.homepageUrl ?: project?.let { "https://modrinth.com/${it.projectType}/${it.slug}" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(content.displayName, style = MaterialTheme.typography.titleMedium, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                content.version?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textSecondary)
                }
                MetaLine(s.contentDetailSize, humanSize(content.sizeBytes, s))
                license?.let {
                    Text(s.contentTabModLicensePrefix(it), style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
                }
                if (content.authors.isNotEmpty()) MetaLine(s.contentDetailAuthors, content.authors.joinToString(", "))
                if (content.dependencies.isNotEmpty()) {
                    Text(s.contentTabModDependencies(content.dependencies.size), style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
                    Text(content.dependencies.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textPrimary)
                }
            }
        },
        confirmButton = {
            pageUrl?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url); onDismiss() }) {
                    Text(s.contentActionOpenPage, color = NxTheme.colors.primary)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.editorClose) } },
        containerColor = NxTheme.colors.surface,
    )
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textPrimary)
    }
}

/** What the image loader should be handed for this icon, if anything. */
private fun ContentIconState.model(): Any? = when (this) {
    is ContentIconState.Bytes -> data
    is ContentIconState.Url -> url
    ContentIconState.None -> null
}
