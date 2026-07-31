package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.data.OptionalContentRules
import hivens.launcher.launch.LauncherController
import hivens.launcher.modrinth.ModrinthClient
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentManager
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.platform.PlatformPaths
import hivens.core.smrt.ModIconResolver
import androidx.compose.runtime.DisposableEffect
import hivens.ui.activity.Selection
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private enum class ContentFilter(val kind: ContentKind?) {
    All(null), Mods(ContentKind.Mod), ResourcePacks(ContentKind.ResourcePack), ShaderPacks(ContentKind.ShaderPack)
}

/**
 * Library PackDetail Content tab. Reads what is ACTUALLY installed under the
 * instance (its `mods/`, `resourcepacks/`, `shaderpacks/` folders) via
 * [InstanceContentScanner] -- origin-agnostic, so it works for mirror, Modrinth
 * and from-scratch packs alike. Display is always on; managing (toggle / delete)
 * is unlocked once the instance is detached from its pack ([PackOrigin.Local]),
 * matching "detach, then make it your own". Detaching itself lives in the pack's
 * Data settings, not here: it costs the instance its update source, so it must not
 * sit one click away on a browsing surface.
 */
@Composable
fun ContentTabPane(instance: PackInstance, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val paths: PlatformPaths = koinInject()
    val instanceDir = remember(instance.instanceDirName) {
        paths.dataDir.resolve("instances").resolve(instance.instanceDirName)
    }
    val scanner: InstanceContentScanner = koinInject()
    val manager = remember { InstanceContentManager() }
    val mirrorClient: IMirrorPackClient = koinInject()
    val controller: LauncherController = koinInject()
    val iconResolver: ModIconResolver = koinInject()
    val modrinth: ModrinthClient = koinInject()
    val scope = rememberCoroutineScope()

    var items by remember(instance.id) { mutableStateOf<List<InstalledContent>?>(null) }
    var scanTick by remember(instance.id) { mutableIntStateOf(0) }
    var query by remember(instance.id) { mutableStateOf("") }
    var filter by remember(instance.id) { mutableStateOf(ContentFilter.All) }
    var pendingDelete by remember(instance.id) { mutableStateOf<InstalledContent?>(null) }
    var pendingBulkDelete by remember(instance.id) { mutableStateOf<List<InstalledContent>>(emptyList()) }
    var selectedKeys by remember(instance.id) { mutableStateOf(emptySet<String>()) }
    val selections: SelectionRegistry = koinInject()

    // The selection lives on the activity surface, so this view publishes it and
    // takes it back down on the way out. Leaving the tab with rows still ticked
    // would otherwise leave a bar offering to delete things the user can no
    // longer see.
    var detailsOf by remember(instance.id) { mutableStateOf<InstalledContent?>(null) }
    var browsing by remember(instance.id) { mutableStateOf(false) }

    LaunchedEffect(instance.id, scanTick) {
        items = withContext(Dispatchers.IO) { scanner.scan(instanceDir) }
    }

    // Icon prefetch: resolve EVERY item's icon up front -- off-screen rows included --
    // into a shared cache the rows read from. Scrolling is then instant, and a row
    // swaps once (placeholder -> final) instead of cascading fill -> initials -> remote
    // -> jar in view. Embedded icon is free; otherwise Modrinth-by-hash, then an
    // undeclared-jar probe, then a letter. Bounded concurrency keeps a big pack from
    // stampeding the network + disk. Keyed on kind+fileName so a toggle never re-resolves.
    val iconCache = remember(instance.id) { mutableStateMapOf<String, ContentIconState>() }
    // Modrinth project resolved by file hash, cached per item (kind-agnostic:
    // mods, resource packs and shaders are all Modrinth project types). Powers the
    // open-page action and fills details a sparse archive leaves blank.
    val projectCache = remember(instance.id) { mutableStateMapOf<String, ModrinthProject?>() }
    LaunchedEffect(items) {
        val list = items ?: return@LaunchedEffect
        val gate = Semaphore(8)
        withContext(Dispatchers.IO) {
            for (c in list) {
                val key = "${c.kind}:${c.fileName}"
                if (iconCache.containsKey(key)) continue
                launch {
                    gate.withPermit {
                        val f = instanceDir.resolve(c.kind.folder())
                            .resolve(if (c.enabled) c.fileName else c.fileName + DISABLED_SUFFIX)
                        val embedded = c.iconBytes
                        iconCache[key] = runCatching {
                            when {
                                embedded != null -> ContentIconState.Bytes(embedded)
                                else -> iconResolver.resolveByFile(f)?.let { ContentIconState.Url(it) }
                                    ?: scanner.probeJarIcon(f)?.let { ContentIconState.Bytes(it) }
                                    ?: ContentIconState.None
                            }
                        }.getOrDefault(ContentIconState.None)
                    }
                }
            }
        }
    }

    // A detached instance is fully user-owned: any mod can be toggled (a `.disabled`
    // rename) or deleted. A tracked MIRROR pack keeps its required mods locked but
    // still lets the user flip OPTIONAL ones -- through the pack's optional-content
    // path (persisted + relabelled), not a raw rename -- so the choice survives a
    // pack update. Other tracked origins (Modrinth / SC) are display-only here.
    val isLocal = instance.packRef.origin == PackOrigin.Local
    val isMirror = instance.packRef.origin == PackOrigin.Mirror

    var manifest by remember(instance.id) { mutableStateOf<SmrtPackManifest?>(null) }
    var optionalState by remember(instance.id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(instance.id) {
        if (!isMirror) return@LaunchedEffect
        val m = runCatching {
            withContext(Dispatchers.IO) {
                val v = instance.pinnedPackVersion ?: instance.packRef.version
                if (!v.isNullOrBlank()) mirrorClient.fetchManifestVersion(instance.packRef.id, v)
                else mirrorClient.fetchManifest(instance.packRef.id)
            }
        }.getOrNull()
        manifest = m
        if (m != null) optionalState = OptionalContentRules.enabledState(m.mods, instance.optionalContent)
    }

    // filename -> manifest entry, for classifying scanned rows on a tracked mirror pack.
    val manifestMods = remember(manifest) { manifest?.mods?.associateBy { it.filename }.orEmpty() }

    val picked = remember(items, selectedKeys) {
        items.orEmpty().filter { it.selectionKey() in selectedKeys }
    }
    // Anything may be picked; what stops an action is the pack owning some of
    // what was picked. Naming the count is the difference between a dead button
    // and one that explains itself.
    val locked = remember(picked, isLocal, manifestMods) {
        picked.count { c ->
            val entry = if (c.kind == ContentKind.Mod) manifestMods[c.fileName] else null
            !(isLocal || c.kind != ContentKind.Mod) && (entry == null || entry.required)
        }
    }
    val blocked = if (locked > 0) s.selectionBlockedByPack(locked) else null
    DisposableEffect(picked) {
        val published = if (picked.isEmpty()) null else Selection(
            items = picked.map { SelectionItem(it.selectionKey(), it.displayName) },
            actions = listOf(
                SelectionAction(SelectionActionKind.Enable, blockedReason = blocked) {
                    scope.launch {
                        picked.forEach { manager.setEnabled(instanceDir, it.kind, it.fileName, true) }
                        selectedKeys = emptySet(); scanTick++
                    }
                },
                SelectionAction(SelectionActionKind.Disable, blockedReason = blocked) {
                    scope.launch {
                        picked.forEach { manager.setEnabled(instanceDir, it.kind, it.fileName, false) }
                        selectedKeys = emptySet(); scanTick++
                    }
                },
                SelectionAction(SelectionActionKind.Delete, blockedReason = blocked) { pendingBulkDelete = picked },
            ),
            clear = { selectedKeys = emptySet() },
        )
        selections.set(published)
        onDispose { selections.clearIf(published) }
    }

    // Flip an optional mod through the persisted optional-content path: apply the
    // toggle (dependency-aware), reflect it immediately, then persist + relabel on
    // the launcher scope (outlives this composable, so navigating away mid-flip
    // still reaches disk). No rescan: [optionalState] already drives the UI.
    fun toggleOptional(fileName: String, enable: Boolean) {
        val m = manifest ?: return
        val next = OptionalContentRules.applyToggle(m.mods, optionalState, fileName, enable)
        optionalState = next
        controller.setOptionalModsAsync(instance, m, OptionalContentRules.togglesFrom(m.mods, next))
    }

    val current = items
    val visible = remember(current, query, filter) {
        (current ?: emptyList()).filter { c ->
            (filter.kind == null || c.kind == filter.kind) &&
                (query.isBlank() || c.displayName.contains(query, ignoreCase = true) || c.fileName.contains(query, ignoreCase = true))
        }
    }

    fun addFiles() {
        scope.launch {
            // Drop into the folder the active filter points at (mods by default).
            val kind = filter.kind ?: ContentKind.Mod
            val extensions = if (kind == ContentKind.Mod) listOf("jar") else listOf("zip")
            val picked = FileKit.openFilePicker(
                type           = FileKitType.File(extensions = extensions),
                mode           = FileKitMode.Multiple(),
                dialogSettings = FileKitDialogSettings(title = s.contentAddFiles),
            )
            val sources = picked.orEmpty().map { Path.of(it.path) }
            if (sources.isNotEmpty()) {
                manager.addFiles(instanceDir, kind, sources)
                scanTick++
            }
        }
    }

    // Resolve an item's Modrinth project by file hash, cached per item. Kind-
    // agnostic (mod / resourcepack / shader all resolve the same way); null means
    // Modrinth does not index this file, and callers fall back to embedded data.
    suspend fun resolveProject(c: InstalledContent): ModrinthProject? {
        val key = "${c.kind}:${c.fileName}"
        if (projectCache.containsKey(key)) return projectCache[key]
        val file = instanceDir.resolve(c.kind.folder())
            .resolve(if (c.enabled) c.fileName else c.fileName + DISABLED_SUFFIX)
        val project = withContext(Dispatchers.IO) {
            val sha1 = runCatching { sha1Of(file) }.getOrNull() ?: return@withContext null
            val version = runCatching { modrinth.versionByHash(sha1) }.getOrNull() ?: return@withContext null
            runCatching { modrinth.resolveProject(version.projectId) }.getOrNull()
        }
        projectCache[key] = project
        return project
    }

    if (browsing) {
        ModBrowser(
            mcVersion = instance.cachedManifest?.minecraftVersion.orEmpty(),
            loader    = instance.cachedManifest?.loaderName
                ?.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }
                ?.lowercase().orEmpty(),
            modsDir   = instanceDir.resolve("mods"),
            modifier  = modifier,
            onBack    = { browsing = false; scanTick++ },
        )
        return
    }

    Column(
        modifier            = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toolbar(
            query          = query,
            onQuery        = { query = it },
            filter         = filter,
            onFilter       = { filter = it },
            // Adding mods is gated behind detach; resource / shader packs can be added
            // any time (switch to their filter to target that folder). "Find projects"
            // is the Modrinth MOD browser, so it stays mod-gated.
            canAdd         = isLocal || filter.kind == ContentKind.ResourcePack || filter.kind == ContentKind.ShaderPack,
            canFindProjects = isLocal,
            onAddFiles     = ::addFiles,
            onFindProjects = { browsing = true },
        )

        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        modifier            = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(items = visible, key = { "${it.kind}:${it.fileName}" }) { c ->
                            // Mods are curated by the pack: on a mirror pack only the
                            // OPTIONAL ones toggle (required locked), full control needs
                            // a detach. Resource / shader packs are cosmetic, not part of
                            // the pack contract -- always user-managed (toggle + delete),
                            // even while tracked.
                            val manifestEntry = if (isMirror && c.kind == ContentKind.Mod) manifestMods[c.fileName] else null
                            val freeEdit = isLocal || c.kind != ContentKind.Mod
                            ContentRow(
                                content          = c,
                                selected         = c.selectionKey() in selectedKeys,
                                selecting        = selectedKeys.isNotEmpty(),
                                onPress          = {
                                    val key = c.selectionKey()
                                    selectedKeys = if (key in selectedKeys) selectedKeys - key
                                                   else selectedKeys + key
                                },
                                onLongPress      = { selectedKeys = selectedKeys + c.selectionKey() },
                                iconState        = iconCache["${c.kind}:${c.fileName}"],
                                effectiveEnabled = when {
                                    manifestEntry != null && !manifestEntry.required -> optionalState[c.fileName] ?: c.enabled
                                    manifestEntry != null -> true            // required -- always on
                                    else -> c.enabled
                                },
                                // Required pack mods get NO toggle (like Modrinth -- you
                                // can't disable what the pack mandates). Only optional
                                // mirror mods + free-edit items (local / resource / shader) toggle.
                                showToggle       = freeEdit || (manifestEntry != null && !manifestEntry.required),
                                onToggle         = when {
                                    manifestEntry != null && !manifestEntry.required -> { enabled -> toggleOptional(c.fileName, enabled) }
                                    freeEdit -> { enabled ->
                                        scope.launch {
                                            manager.setEnabled(instanceDir, c.kind, c.fileName, enabled)
                                            scanTick++
                                        }
                                    }
                                    else -> { _ -> }
                                },
                                onDelete         = if (freeEdit) ({ pendingDelete = c }) else null,
                                onDetails        = { detailsOf = c },
                                resolveProject   = { resolveProject(c) },
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

    if (pendingBulkDelete.isNotEmpty()) {
        val targets = pendingBulkDelete
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentBulkDeleteBody(targets.size),
            confirmLabel = s.editorDelete,
            onConfirm    = {
                scope.launch {
                    targets.forEach { manager.delete(instanceDir, it.kind, it.fileName) }
                    selectedKeys = emptySet(); pendingBulkDelete = emptyList(); scanTick++
                }
            },
            onDismiss    = { pendingBulkDelete = emptyList() },
        )
    }

    pendingDelete?.let { target ->
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentDeleteBody,
            confirmLabel = s.editorDelete,
            onConfirm    = { scope.launch { manager.delete(instanceDir, target.kind, target.fileName); scanTick++ } },
            onDismiss    = { pendingDelete = null },
        )
    }

    detailsOf?.let { target ->
        ContentDetailsDialog(
            content        = target,
            resolveProject = { resolveProject(target) },
            onDismiss      = { detailsOf = null },
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
 * One content row. [effectiveEnabled] drives both the dim and the Switch -- for a
 * tracked optional mod it is the pack's optional-content state, which may differ
 * from the raw on-disk `.disabled` until the async relabel lands. [showToggle]
 * renders the Switch (off for display-only tracked rows); [toggleLocked] shows it
 * checked-but-disabled (a required mirror mod). [onDelete] is null unless the row
 * is user-owned (a detached instance).
 */
@Composable
private fun ContentRow(
    content: InstalledContent,
    iconState: ContentIconState?,
    effectiveEnabled: Boolean,
    showToggle: Boolean,
    // Every row selects. A control that appears on some rows and not others
    // ragged the left edge of the whole list and told the user the difference
    // before they had asked a question it answers; what a row can have DONE to it
    // is a property of the action, not of whether it may be pointed at.
    selected: Boolean,
    selecting: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDetails: () -> Unit,
    resolveProject: suspend () -> ModrinthProject?,
) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    val dim = if (effectiveEnabled) 1f else 0.5f
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) NxTheme.colors.primary.copy(alpha = 0.14f)
                else glassSurfaceAlpha(0.4f),
            )
            .pointerInput(selecting) {
                detectTapGestures(
                    // A press picks while a selection is running, so the second
                    // and third items cost one tap each rather than a hold apiece.
                    onTap = { if (selecting) onPress() },
                    onLongPress = { onLongPress() },
                )
            }
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
        if (showToggle) {
            NxSwitch(
                checked         = effectiveEnabled,
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

/** Pre-resolved icon for a content row: embedded/probed jar bytes, a remote URL, or none. */
private sealed class ContentIconState {
    class Bytes(val data: ByteArray) : ContentIconState()
    class Url(val url: String) : ContentIconState()
    object None : ContentIconState()
}

private fun ContentKind.folder(): String = when (this) {
    ContentKind.Mod -> "mods"
    ContentKind.ResourcePack -> "resourcepacks"
    ContentKind.ShaderPack -> "shaderpacks"
}

private const val DISABLED_SUFFIX = ".disabled"

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
                MetaLine(s.contentDetailSize, humanSize(content.sizeBytes))
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

private fun sha1Of(file: Path): String {
    val md = MessageDigest.getInstance("SHA-1")
    Files.newInputStream(file).use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024      -> "${bytes / 1024} KB"
    else               -> "$bytes B"
}

/** Stable across a rescan: kind plus filename is what the row is keyed on too. */
private fun InstalledContent.selectionKey(): String = "$kind:$fileName"
