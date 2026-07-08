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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxSwitch
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import java.nio.file.Path
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
 * matching "detach, then make it your own". A tracked pack shows a detach prompt.
 */
@Composable
fun ContentTabPane(instance: PackInstance, onDetach: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val paths: PlatformPaths = koinInject()
    val instanceDir = remember(instance.instanceDirName) {
        paths.dataDir.resolve("instances").resolve(instance.instanceDirName)
    }
    val scanner = remember { InstanceContentScanner() }
    val manager = remember { InstanceContentManager() }
    val mirrorClient: IMirrorPackClient = koinInject()
    val controller: LauncherController = koinInject()
    val iconResolver: ModIconResolver = koinInject()
    val scope = rememberCoroutineScope()

    var items by remember(instance.id) { mutableStateOf<List<InstalledContent>?>(null) }
    var scanTick by remember(instance.id) { mutableIntStateOf(0) }
    var query by remember(instance.id) { mutableStateOf("") }
    var filter by remember(instance.id) { mutableStateOf(ContentFilter.All) }
    var pendingDelete by remember(instance.id) { mutableStateOf<InstalledContent?>(null) }
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
        if (!isLocal) DetachBanner(
            // On a mirror pack the optional toggles below already work; the banner
            // sells detach for the rest (add / delete / required). Other origins
            // can't manage anything until detached -- the stronger copy.
            body     = if (isMirror) s.contentTrackedOptionalBody else s.contentDetachBody,
            onDetach = onDetach,
        )
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

    pendingDelete?.let { target ->
        DestructiveConfirmDialog(
            title        = s.contentDeleteTitle,
            body         = s.contentDeleteBody,
            confirmLabel = s.editorDelete,
            onConfirm    = { scope.launch { manager.delete(instanceDir, target.kind, target.fileName); scanTick++ } },
            onDismiss    = { pendingDelete = null },
        )
    }
}

@Composable
private fun DetachBanner(body: String, onDetach: () -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.contentDetachTitle, style = MaterialTheme.typography.titleSmall, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
        }
        Button(
            onClick = onDetach,
            shape   = MaterialTheme.shapes.small,
            colors  = ButtonDefaults.buttonColors(containerColor = NxTheme.colors.primary, contentColor = Color.White),
        ) { Text(s.contentDetachButton, fontWeight = FontWeight.SemiBold) }
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
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val dim = if (effectiveEnabled) 1f else 0.5f
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(glassSurfaceAlpha(0.4f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentIcon(iconState, content.fileName, content.displayName, dim)
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
        if (onDelete != null) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(6.dp)) {
                Symbol(NxIcon.Delete, contentDescription = null, tint = NxTheme.colors.error, size = 18.dp)
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
            else      -> Button(
                onClick = onInstall,
                shape   = MaterialTheme.shapes.small,
                colors  = ButtonDefaults.buttonColors(containerColor = NxTheme.colors.primary, contentColor = Color.White),
            ) { Text(s.browseDetailInstallButton, fontWeight = FontWeight.SemiBold) }
        }
    }
}
