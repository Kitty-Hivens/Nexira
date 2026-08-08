package hivens.ui.screens.library.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.smrt.ModIconResolver
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentManager
import hivens.launcher.instance.ContentFolderWatch
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.launch.LauncherController
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.platform.PlatformPaths
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal enum class ContentFilter(val kind: ContentKind?) {
    All(null), Mods(ContentKind.Mod), ResourcePacks(ContentKind.ResourcePack), ShaderPacks(ContentKind.ShaderPack)
}

/** Pre-resolved icon for a content row: embedded/probed jar bytes, a remote URL, or none. */
internal sealed class ContentIconState {
    class Bytes(val data: ByteArray) : ContentIconState()
    class Url(val url: String) : ContentIconState()
    object None : ContentIconState()
}

/**
 * State holder for [ContentTabPane]. Owns the scan of what is installed, the
 * pack manifest that says which of it the pack curates, the icon and Modrinth
 * caches, and every mutation (toggle, add, delete). The composable renders these
 * and forwards intents.
 *
 * A rescan follows every mutation from in here, so no caller has to remember to
 * ask for one, and the icon prefetch is re-launched with the new list -- the
 * previous one cancelled, the cache keyed by kind and file name, so a toggle
 * re-resolves nothing.
 */
@Stable
internal class ContentTabState(
    val instance: PackInstance,
    val instanceDir: Path,
    private val scanner: InstanceContentScanner,
    private val manager: InstanceContentManager,
    private val mirrorClient: IMirrorPackClient,
    private val controller: LauncherController,
    private val iconResolver: ModIconResolver,
    private val modrinth: ModrinthClient,
    private val watch: ContentFolderWatch,
    private val scope: CoroutineScope,
) {
    /**
     * A detached instance is fully user-owned: any mod can be toggled (a
     * `.disabled` rename) or deleted. A tracked MIRROR pack keeps its required
     * mods locked but still lets the user flip OPTIONAL ones -- through the
     * pack's optional-content path (persisted + relabelled), not a raw rename --
     * so the choice survives a pack update. Other tracked origins (Modrinth /
     * SC) are display-only here.
     */
    val isLocal = instance.packRef.origin == PackOrigin.Local
    val isMirror = instance.packRef.origin == PackOrigin.Mirror

    /** Null until the first scan lands, which the pane renders as loading. */
    var items by mutableStateOf<List<InstalledContent>?>(null)
        private set

    var query by mutableStateOf("")
    var filter by mutableStateOf(ContentFilter.All)

    var selectedKeys by mutableStateOf(emptySet<String>())
        private set
    var pendingDelete by mutableStateOf<InstalledContent?>(null)
        private set
    var pendingBulkDelete by mutableStateOf<List<InstalledContent>>(emptyList())
        private set
    var detailsOf by mutableStateOf<InstalledContent?>(null)
    var browsing by mutableStateOf(false)
        private set

    /**
     * Icons for every scanned item, off-screen rows included, so scrolling is
     * instant and a row swaps once (placeholder to final) instead of cascading
     * fill, initials, remote, jar as it comes into view.
     */
    val iconCache = mutableStateMapOf<String, ContentIconState>()

    // Modrinth project resolved by file hash, cached per item (kind-agnostic:
    // mods, resource packs and shaders are all Modrinth project types). Powers
    // the open-page action and fills details a sparse archive leaves blank.
    private val projectCache = mutableStateMapOf<String, ModrinthProject?>()

    private var manifest by mutableStateOf<SmrtPackManifest?>(null)
    private var optionalState by mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** filename -> manifest entry, for classifying rows on a tracked mirror pack. */
    private val manifestMods: Map<String, SmrtModEntry> by derivedStateOf {
        manifest?.mods?.associateBy { it.filename }.orEmpty()
    }

    /** What the list shows: the scan narrowed by the filter chips and the search. */
    val visible: List<InstalledContent> by derivedStateOf { filterContent(items.orEmpty(), query, filter) }

    /** The ticked rows, in list order. */
    val picked: List<InstalledContent> by derivedStateOf {
        items.orEmpty().filter { it.selectionKey() in selectedKeys }
    }

    /**
     * How many of [picked] the pack owns. Anything may be ticked; what stops an
     * action is the pack owning some of what was ticked, and naming the count is
     * the difference between a dead button and one that explains itself.
     */
    val lockedCount: Int by derivedStateOf { lockedCount(picked, isLocal, manifestMods) }

    // Cancelled and replaced whenever the list changes, so a rescan does not
    // leave a previous prefetch racing the new one over the same keys.
    private var iconJob: Job? = null

    /**
     * The installed list and its icons, alongside the pack manifest on a mirror
     * pack. Alongside rather than after: the manifest is a network fetch and only
     * decides how rows are labelled, so waiting for it would hold the list -- which
     * is a local directory read -- behind the slowest thing on the screen.
     */
    suspend fun load() = coroutineScope {
        if (isMirror) launch { loadManifest() }
        rescan()
    }

    private suspend fun loadManifest() {
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

    /**
     * Rescans whenever the content folders change underneath the launcher.
     *
     * A player who drops a jar into `mods/` from a file manager expects to find it
     * listed when they switch back. Every other rescan here follows a mutation made
     * from inside this screen, so before this the only way to see an outside change
     * was to leave the tab and come back.
     *
     * Runs for as long as it is collected -- the effect that starts it is keyed on
     * this state, so leaving the screen stops the polling.
     */
    suspend fun watchContentFolders() {
        watch.changes(instanceDir).collect { rescan() }
    }

    private suspend fun rescan() {
        val scanned = withContext(Dispatchers.IO) { scanner.scan(instanceDir) }
        items = scanned
        prefetchIcons(scanned)
    }

    /**
     * Embedded icon first (free), then Modrinth by file hash, then a probe of the
     * jar, then nothing and the row draws a letter. Bounded concurrency keeps a
     * big pack from stampeding the network and the disk at once.
     */
    private fun prefetchIcons(list: List<InstalledContent>) {
        iconJob?.cancel()
        iconJob = scope.launch {
            val gate = Semaphore(ICON_PREFETCH_CONCURRENCY)
            withContext(Dispatchers.IO) {
                for (c in list) {
                    val key = c.selectionKey()
                    if (iconCache.containsKey(key)) continue
                    launch {
                        gate.withPermit {
                            val file = fileOf(c)
                            val embedded = c.iconBytes
                            iconCache[key] = runCatching {
                                when {
                                    embedded != null -> ContentIconState.Bytes(embedded)
                                    else -> iconResolver.resolveByFile(file)?.let { ContentIconState.Url(it) }
                                        ?: scanner.probeJarIcon(file)?.let { ContentIconState.Bytes(it) }
                                        ?: ContentIconState.None
                                }
                            }.getOrDefault(ContentIconState.None)
                        }
                    }
                }
            }
        }
    }

    /** What one row may do, given the pack's contract and who owns the instance. */
    fun rulesFor(content: InstalledContent): ContentRowRules = contentRowRules(
        content         = content,
        manifestEntry   = entryFor(content),
        isLocal         = isLocal,
        optionalEnabled = optionalState[content.fileName],
    )

    fun iconFor(content: InstalledContent): ContentIconState? = iconCache[content.selectionKey()]

    /**
     * Flip one row. An optional mod on a tracked pack goes through the pack's
     * optional-content path -- persisted and relabelled on the launcher scope, so
     * navigating away mid-flip still reaches disk, and the choice survives a pack
     * update. A user-owned file is renamed on disk. A required mod does neither.
     */
    fun toggle(content: InstalledContent, enabled: Boolean) {
        val rules = rulesFor(content)
        when {
            rules.optional -> toggleOptional(content.fileName, enabled)
            rules.showToggle -> scope.launch {
                manager.setEnabled(instanceDir, content.kind, content.fileName, enabled)
                rescan()
            }
        }
    }

    private fun toggleOptional(fileName: String, enable: Boolean) {
        val m = manifest ?: return
        // No rescan: [optionalState] already drives the UI, and the relabel on
        // disk lands asynchronously behind it.
        val next = OptionalContentRules.applyToggle(m.mods, optionalState, fileName, enable)
        optionalState = next
        controller.setOptionalModsAsync(instance, m, OptionalContentRules.togglesFrom(m.mods, next))
    }

    /**
     * The same routing a single row does, over a whole selection.
     *
     * A bulk action that knew only the on-disk half wrote there for both, and
     * since the list renders from the optional state, the pack's mods appeared
     * not to react at all -- the write went somewhere nothing was reading, and
     * the next sync would have undone it. The optional half is folded into one
     * state and persisted once: applying the toggles one at a time from a
     * captured state would keep only the last.
     */
    fun setEnabledForSelection(targets: List<InstalledContent>, enable: Boolean) {
        val (optional, onDisk) = targets.partition { rulesFor(it).optional }
        val m = manifest
        if (m != null && optional.isNotEmpty()) {
            var next = optionalState
            optional.forEach { next = OptionalContentRules.applyToggle(m.mods, next, it.fileName, enable) }
            optionalState = next
            controller.setOptionalModsAsync(instance, m, OptionalContentRules.togglesFrom(m.mods, next))
        }
        if (onDisk.isNotEmpty()) {
            scope.launch {
                onDisk.forEach { manager.setEnabled(instanceDir, it.kind, it.fileName, enable) }
                rescan()
            }
        }
        clearSelection()
    }

    // -- selection ------------------------------------------------------------

    fun setSelected(key: String, on: Boolean) {
        selectedKeys = if (on) selectedKeys + key else selectedKeys - key
    }

    fun clearSelection() {
        selectedKeys = emptySet()
    }

    // -- add / delete ---------------------------------------------------------

    /** Drop files into the folder the active filter points at (mods by default). */
    fun addFiles(dialogTitle: String) {
        scope.launch {
            val kind = filter.kind ?: ContentKind.Mod
            val extensions = if (kind == ContentKind.Mod) listOf("jar") else listOf("zip")
            val picked = FileKit.openFilePicker(
                type           = FileKitType.File(extensions = extensions),
                mode           = FileKitMode.Multiple(),
                dialogSettings = FileKitDialogSettings(title = dialogTitle),
            )
            val sources = picked.orEmpty().map { Path.of(it.path) }
            if (sources.isNotEmpty()) {
                manager.addFiles(instanceDir, kind, sources)
                rescan()
            }
        }
    }

    fun requestDelete(content: InstalledContent) {
        pendingDelete = content
    }

    fun requestBulkDelete() {
        pendingBulkDelete = picked
    }

    fun cancelDelete() {
        pendingDelete = null
        pendingBulkDelete = emptyList()
    }

    /**
     * Carry out whichever delete was asked for. A bulk delete takes the ticks
     * down with it; a single row from its own menu leaves a selection alone,
     * since it was never acting on one.
     */
    fun confirmDelete() {
        val single = pendingDelete
        val bulk = pendingBulkDelete
        pendingDelete = null
        pendingBulkDelete = emptyList()
        val targets = if (single != null) listOf(single) else bulk
        if (targets.isEmpty()) return
        scope.launch {
            targets.forEach { manager.delete(instanceDir, it.kind, it.fileName) }
            if (single == null) clearSelection()
            rescan()
        }
    }

    // -- browse ---------------------------------------------------------------

    fun startBrowsing() {
        browsing = true
    }

    /** Coming back from the browser picks up whatever it downloaded. */
    fun stopBrowsing() {
        browsing = false
        scope.launch { rescan() }
    }

    // -- modrinth -------------------------------------------------------------

    /**
     * The item's Modrinth project by file hash, cached per item. Kind-agnostic
     * (mod, resource pack and shader all resolve the same way); null means
     * Modrinth does not index this file and callers fall back to embedded data.
     */
    suspend fun resolveProject(content: InstalledContent): ModrinthProject? {
        val key = content.selectionKey()
        if (projectCache.containsKey(key)) return projectCache[key]
        val file = fileOf(content)
        val project = withContext(Dispatchers.IO) {
            val sha1 = runCatching { sha1Of(file) }.getOrNull() ?: return@withContext null
            val version = runCatching { modrinth.versionByHash(sha1) }.getOrNull() ?: return@withContext null
            runCatching { modrinth.resolveProject(version.projectId) }.getOrNull()
        }
        projectCache[key] = project
        return project
    }

    // -- internals ------------------------------------------------------------

    /** The manifest entry that governs [content], or null when the pack does not curate it. */
    private fun entryFor(content: InstalledContent): SmrtModEntry? =
        if (isMirror && content.kind == ContentKind.Mod) manifestMods[content.fileName] else null

    private fun fileOf(content: InstalledContent): Path =
        instanceDir.resolve(content.kind.folder())
            .resolve(if (content.enabled) content.fileName else content.fileName + DISABLED_SUFFIX)

    private companion object {
        const val ICON_PREFETCH_CONCURRENCY = 8
    }
}

@Composable
internal fun rememberContentTabState(instance: PackInstance): ContentTabState {
    val paths: PlatformPaths = koinInject()
    val scanner: InstanceContentScanner = koinInject()
    val mirrorClient: IMirrorPackClient = koinInject()
    val controller: LauncherController = koinInject()
    val iconResolver: ModIconResolver = koinInject()
    val modrinth: ModrinthClient = koinInject()
    val scope = rememberCoroutineScope()
    return remember(instance.id) {
        ContentTabState(
            instance     = instance,
            instanceDir  = paths.dataDir.resolve("instances").resolve(instance.instanceDirName),
            scanner      = scanner,
            manager      = InstanceContentManager(),
            mirrorClient = mirrorClient,
            controller   = controller,
            iconResolver = iconResolver,
            modrinth     = modrinth,
            watch        = ContentFolderWatch(),
            scope        = scope,
        )
    }
}

// ── Pure rules (unit-tested without a scanner, a manifest client or a disk) ──

/**
 * What a single row may do.
 *
 * [effectiveEnabled] drives both the dim and the switch: for a tracked optional
 * mod it is the pack's optional-content state, which may differ from the raw
 * on-disk `.disabled` until the async relabel lands. [optional] says the toggle
 * routes through the pack rather than through a rename. A required pack mod gets
 * no toggle at all -- you cannot disable what the pack mandates.
 */
internal data class ContentRowRules(
    val effectiveEnabled: Boolean,
    val showToggle: Boolean,
    val optional: Boolean,
    val canDelete: Boolean,
)

/**
 * [manifestEntry] is the pack's entry for this file, and null both when the pack
 * does not curate it and when the instance is not on a mirror pack at all.
 * [optionalEnabled] is the pack's optional-content state for the file, if any.
 *
 * Resource and shader packs are cosmetic rather than part of the pack contract,
 * so they stay user-managed even while the instance is tracked; mods do not.
 */
internal fun contentRowRules(
    content: InstalledContent,
    manifestEntry: SmrtModEntry?,
    isLocal: Boolean,
    optionalEnabled: Boolean?,
): ContentRowRules {
    val freeEdit = isLocal || content.kind != ContentKind.Mod
    val optional = manifestEntry != null && !manifestEntry.required
    return ContentRowRules(
        effectiveEnabled = when {
            optional              -> optionalEnabled ?: content.enabled
            manifestEntry != null -> true // required -- always on
            else                  -> content.enabled
        },
        showToggle = freeEdit || optional,
        optional   = optional,
        canDelete  = freeEdit,
    )
}

/** The filter chips and the search box, over the scan. */
internal fun filterContent(
    items: List<InstalledContent>,
    query: String,
    filter: ContentFilter,
): List<InstalledContent> = items.filter { c ->
    (filter.kind == null || c.kind == filter.kind) &&
        (
            query.isBlank() ||
                c.displayName.contains(query, ignoreCase = true) ||
                c.fileName.contains(query, ignoreCase = true)
            )
}

/** How many of [picked] belong to the pack rather than to the user. */
internal fun lockedCount(
    picked: List<InstalledContent>,
    isLocal: Boolean,
    manifestMods: Map<String, SmrtModEntry>,
): Int = picked.count { c ->
    val freeEdit = isLocal || c.kind != ContentKind.Mod
    val entry = if (c.kind == ContentKind.Mod) manifestMods[c.fileName] else null
    !freeEdit && (entry == null || entry.required)
}

/** Stable across a rescan: kind plus filename is what the row is keyed on too. */
internal fun InstalledContent.selectionKey(): String = "$kind:$fileName"

internal fun ContentKind.folder(): String = when (this) {
    ContentKind.Mod -> "mods"
    ContentKind.ResourcePack -> "resourcepacks"
    ContentKind.ShaderPack -> "shaderpacks"
}

internal const val DISABLED_SUFFIX = ".disabled"

internal fun sha1Of(file: Path): String {
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
