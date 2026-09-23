package hivens.ui.screens.library.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.interfaces.IMirrorPackClient
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.smrt.ModIconResolver
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.ContentRef
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentManager
import hivens.launcher.instance.ContentFolderWatch
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.instance.ModUpdate
import hivens.launcher.instance.PackPlacedContent
import hivens.launcher.instance.folderName
import hivens.launcher.instance.pathIn
import hivens.launcher.instance.swapFor
import hivens.launcher.launch.LauncherController
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.platform.PlatformPaths
import hivens.ui.utils.pickFiles
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** Whether a row is on, off, or either -- as the row itself reads it. */
internal enum class ContentStatus { Any, Enabled, Disabled }

/** Who put a file there: the pack, or the player. */
internal enum class ContentOwner { Any, Pack, User }

/**
 * The axes that narrow WITHIN a section, as opposed to [ContentFilter], which
 * chooses the section itself. Optional is not a kind of thing -- it is a property
 * of a mod the pack curates -- and neither is on-or-off, or who added it, so none
 * of the three belongs in a row of section chips.
 */
internal data class ContentFilters(
    val optionalOnly: Boolean = false,
    val status: ContentStatus = ContentStatus.Any,
    val owner: ContentOwner = ContentOwner.Any,
) {
    /** How many axes are narrowing the list right now -- what the trigger badges. */
    val activeCount: Int =
        (if (optionalOnly) 1 else 0) +
            (if (status != ContentStatus.Any) 1 else 0) +
            (if (owner != ContentOwner.Any) 1 else 0)

    val isEmpty: Boolean get() = activeCount == 0
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
    initialInstance: PackInstance,
    val instanceDir: Path,
    private val scanner: InstanceContentScanner,
    private val manager: InstanceContentManager,
    private val mirrorClient: IMirrorPackClient,
    private val controller: LauncherController,
    private val iconResolver: ModIconResolver,
    private val modrinth: ModrinthClient,
    private val updater: InstanceContentUpdater,
    private val watch: ContentFolderWatch,
    /**
     * Answers that outlive this tab. Read straight into [iconCache] when a scan
     * lands, so a return visit paints the icons it already knew on the first
     * frame instead of re-hashing every jar in the folder to find them again.
     */
    private val icons: ContentIconStore,
    private val scope: CoroutineScope,
    /**
     * Where a change to the files on disk runs.
     *
     * The app's scope, not the screen's. Enabling, disabling and deleting were
     * launched on the composition, so leaving the tab part-way through cancelled
     * them: a bulk delete of forty mods removed some and left the rest, and the list
     * on the way back disagreed with what was asked for. What the tab reads may stop
     * when the tab goes; what it has already told the disk to do may not.
     */
    private val writeScope: CoroutineScope,
) {
    /**
     * The record this tab is looking at. It is rewritten under the tab -- an
     * optional flipped in the settings window, a build applied on the app scope,
     * a detach -- and the tab is one of its readers, not its owner, so it follows
     * rather than keeps the copy it opened with. Writing back from a captured
     * copy is also how a rename made meanwhile got clobbered.
     */
    var instance by mutableStateOf(initialInstance)
        private set

    /**
     * A detached instance is fully user-owned: any mod can be toggled (a
     * `.disabled` rename) or deleted. A tracked MIRROR pack keeps its required
     * mods locked but still lets the user flip OPTIONAL ones -- through the
     * pack's optional-content path (persisted + relabelled), not a raw rename --
     * so the choice survives a pack update. Another tracked origin answers
     * through [placed] instead, per file.
     *
     * Read off [instance] rather than fixed at construction: a detach turns a
     * tracked pack into a local one without the tab being rebuilt.
     */
    val isLocal: Boolean get() = instance.packRef.origin == PackOrigin.Local
    val isMirror: Boolean get() = instance.packRef.origin == PackOrigin.Mirror

    /**
     * Selection keys for the files the pack itself placed, or null when this
     * instance keeps no record of that.
     *
     * The mirror answers the same question from its manifest and does not need
     * this. Every other tracked source did not answer it at all, and the only
     * safe reading of "unknown" was that the pack owned the lot -- so a Modrinth
     * pack showed its mods with no switch, no delete, no way to add one, and
     * counted the player's own files among the pack's. With the record, the
     * question is answered per file, exactly as far as it is actually known.
     */
    private var placed by mutableStateOf<Set<String>?>(null)

    /** Null until the first scan lands, which the pane renders as loading. */
    var items by mutableStateOf<List<InstalledContent>?>(null)
        private set

    var query by mutableStateOf("")
    var filter by mutableStateOf(ContentFilter.All)
    var filters by mutableStateOf(ContentFilters())

    var selectedKeys by mutableStateOf(emptySet<String>())
        private set
    var pendingDelete by mutableStateOf<InstalledContent?>(null)
        private set
    var pendingBulkDelete by mutableStateOf<List<InstalledContent>>(emptyList())
        private set

    // -- updates --------------------------------------------------------------

    /**
     * What Modrinth says is newer, per installed file. Empty until a check runs,
     * which is not the same as "everything is current" -- [checked] separates the
     * two, so the toolbar can stay quiet instead of claiming a folder is up to
     * date before anyone has asked.
     */
    var updates by mutableStateOf<Map<ContentRef, ModUpdate>>(emptyMap())
        private set
    var checkingUpdates by mutableStateOf(false)
        private set
    var checked by mutableStateOf(false)
        private set

    /**
     * The last check could not be completed. Distinct from finding nothing: one
     * says the folder is current, the other says nobody managed to ask.
     */
    var checkFailed by mutableStateOf(false)
        private set

    /** The batch this instance has in flight, or the outcome of its last one. */
    var updateRun by mutableStateOf<InstanceContentUpdater.Run?>(null)
        private set

    /** The row whose version list is open, and what has been loaded for it. */
    var versionsOf by mutableStateOf<InstalledContent?>(null)
        private set
    var versionList by mutableStateOf<List<ModrinthVersion>?>(null)
        private set
    /**
     * Two different empty lists, and they need different words: Modrinth has no
     * record of this file at all, or it has and the fetch did not come back.
     */
    var versionsUnknown by mutableStateOf(false)
        private set
    var versionsFailed by mutableStateOf(false)
        private set
    var switchingTo by mutableStateOf<String?>(null)
        private set

    /**
     * Updates for rows that are still on disk under the name they were found by.
     *
     * An applied update renames the file, so its entry here would otherwise keep
     * offering a version that is already installed until the next check came back.
     */
    val liveUpdates: Map<ContentRef, ModUpdate> by derivedStateOf {
        val present = items.orEmpty().mapTo(mutableSetOf()) { ContentRef(it.kind, it.fileName) }
        updates.filterKeys { it in present }
    }

    /** Rows the player may actually have replaced, which is what "update all" means here. */
    val updatable: List<InstalledContent> by derivedStateOf {
        items.orEmpty().filter { rulesFor(it).canDelete }
    }

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

    // The Modrinth version a file IS, resolved by the same hash lookup the project
    // resolution already pays for. The version picker needs it to mark which row of
    // the list the instance is actually sitting on.
    private val versionCache = mutableStateMapOf<String, ModrinthVersion>()

    private var manifest by mutableStateOf<SmrtPackManifest?>(null)

    /** The build [manifest] was fetched for; null until one is loaded. */
    private var manifestVersion: String? = null
    private var optionalState by mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** filename -> manifest entry, for classifying rows on a tracked mirror pack. */
    private val manifestMods: Map<String, SmrtModEntry> by derivedStateOf {
        manifest?.mods?.associateBy { it.filename }.orEmpty()
    }

    /**
     * Filenames the pack marks optional, i.e. the ones a player may turn off.
     *
     * Mods only, and deliberately: the whole optional-content pipeline is -- the
     * rules read `manifest.mods`, the toggles persist per mod, and the relabel
     * renames jars. A manifest asset carries the same `required` flag, but nothing
     * acts on it, so listing an optional resource pack here would offer a switch
     * that does not exist.
     */
    private val optionalNames: Set<String> by derivedStateOf {
        manifestMods.values.filterNot { it.required }.map { it.filename }.toSet()
    }

    /**
     * Whether the optional axis has anything to offer. A local pack curates nothing
     * and a pack whose manifest names no optional mods would give that filter an
     * empty list to show, which is worse than not offering it at all.
     */
    val hasOptional: Boolean by derivedStateOf { optionalNames.isNotEmpty() }

    /**
     * Everything the pack ships that this tab can show, as selection keys.
     *
     * Mods AND assets: a resource pack or a shader the pack ships lives in the
     * manifest's assets with a path rather than a bare filename, and reading only
     * the mods filed every one of them under the player. Keyed by kind as well as
     * name, since the same file name in two folders is two different rows.
     *
     * Assets outside the folders this tab lists -- configs, scripts -- have no row
     * to classify and are skipped.
     */
    private val packContentKeys: Set<String> by derivedStateOf {
        val m = manifest ?: return@derivedStateOf placed.orEmpty()
        buildSet {
            m.mods.forEach { add(contentKey(ContentKind.Mod, it.filename)) }
            m.assets.forEach { asset ->
                val kind = kindOfDest(asset.dest) ?: return@forEach
                add(contentKey(kind, asset.dest.substringAfterLast('/')))
            }
        }
    }

    /** Whether the pack curates anything here, which is what the owner axis sorts by. */
    val hasPackContent: Boolean by derivedStateOf { packContentKeys.isNotEmpty() }

    /** What the list shows: the scan narrowed by the section, the filters and the search. */
    val visible: List<InstalledContent> by derivedStateOf {
        filterContent(
            items          = items.orEmpty(),
            query          = query,
            filter         = filter,
            filters        = filters,
            optionalNames  = optionalNames,
            packKeys       = packContentKeys,
            effectiveOn    = { rulesFor(it).effectiveEnabled },
        )
    }

    /** How many rows the scan holds before the filters narrow it -- the panel's denominator. */
    val scannedCount: Int by derivedStateOf { items.orEmpty().size }

    /** The ticked rows, in list order. */
    val picked: List<InstalledContent> by derivedStateOf {
        items.orEmpty().filter { it.selectionKey() in selectedKeys }
    }

    /**
     * How many of [picked] the pack owns. Anything may be ticked; what stops an
     * action is the pack owning some of what was ticked, and naming the count is
     * the difference between a dead button and one that explains itself.
     */
    val lockedCount: Int by derivedStateOf { lockedCount(picked, ::userOwns, manifestMods) }

    // Cancelled and replaced whenever the list changes, so a rescan does not
    // leave a previous prefetch racing the new one over the same keys.
    private var iconJob: Job? = null

    /** The open row's version lookup, for the same reason [iconJob] exists. */
    private var versionsJob: Job? = null

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

    /**
     * Takes a rewritten record. A new build means a different manifest, so the rows
     * are re-classified against the one that is actually installed; anything else
     * only has to re-seed the optional state, which is the field the settings window
     * writes to as well.
     *
     * "New build" is asked of the manifest that is loaded rather than of the record
     * this replaces: a re-seed cancelled halfway -- another write lands while the
     * fetch is in flight -- would otherwise leave the tab classifying rows against
     * the previous build's manifest with nothing left to say so.
     */
    suspend fun adopt(updated: PackInstance) {
        if (updated == instance) return
        instance = updated
        if (!isMirror) return
        if (manifest == null || manifestVersion != updated.installedVersion()) loadManifest()
        else manifest?.let { optionalState = OptionalContentRules.enabledState(it.mods, updated.optionalContent) }
    }

    private fun PackInstance.installedVersion(): String? = pinnedPackVersion ?: packRef.version

    private suspend fun loadManifest() {
        val m = runCatching {
            withContext(Dispatchers.IO) {
                val v = instance.installedVersion()
                if (!v.isNullOrBlank()) mirrorClient.fetchManifestVersion(instance.packRef.id, v)
                else mirrorClient.fetchManifest(instance.packRef.id)
            }
        }.getOrNull()
        manifest = m
        manifestVersion = if (m != null) instance.installedVersion() else null
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
        // Re-read alongside the scan rather than once at open: an update rewrites
        // the record and the folder together, and the rows would otherwise keep
        // classifying against the file set of the build that was replaced.
        placed = withContext(Dispatchers.IO) { placedKeysFrom(PackPlacedContent.paths(instanceDir)) }
        items = scanned
        prefetchIcons(scanned)
    }

    /**
     * Embedded icon first (free), then Modrinth by file hash, then a probe of the
     * jar, then nothing and the row draws a letter. Bounded concurrency keeps a
     * big pack from stampeding the network and the disk at once.
     *
     * Only answers reach the cache. Every rescan cancels the prefetch in flight,
     * and a file that has just been installed is precisely the one being resolved
     * when the next rescan lands, so a cancellation written down as "no icon"
     * would single out the mod the person had just chosen.
     */
    private fun prefetchIcons(list: List<InstalledContent>) {
        // What the process already knows, taken synchronously and before anything
        // is drawn. Only what is left over is worth a coroutine.
        list.forEach { c ->
            val key = c.selectionKey()
            if (iconCache.containsKey(key)) return@forEach
            icons.get(storeKey(c))?.let { iconCache[key] = it }
        }
        iconJob?.cancel()
        iconJob = scope.launch {
            val gate = Semaphore(ICON_PREFETCH_CONCURRENCY)
            withContext(Dispatchers.IO) {
                for (c in list) {
                    val key = c.selectionKey()
                    if (iconCache.containsKey(key)) continue
                    launch {
                        val resolved = gate.withPermit {
                            val file = fileOf(c)
                            val embedded = c.iconBytes
                            // The archive's own art, which is local and final.
                            val probed = { scanner.probeJarIcon(file)?.let { ContentIconState.Bytes(it) } }
                            when {
                                embedded != null -> ContentIconState.Bytes(embedded)
                                else -> try {
                                    iconResolver.resolveByFile(file)?.let { ContentIconState.Url(it) }
                                        ?: probed()
                                        ?: ContentIconState.None
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // The catalogue could not be ASKED. The archive
                                    // can still answer, and its answer is final; a
                                    // letter is not, so where the archive has
                                    // nothing either, nothing is written down and
                                    // the next scan asks again. Writing the letter
                                    // here is what kept a just-installed mod on one
                                    // for the rest of the visit.
                                    probed()
                                }
                            }
                        } ?: return@launch
                        icons.put(storeKey(c), resolved)
                        // Resolved off-thread, written on the composition's own
                        // thread. Assigning straight from a worker used whatever
                        // snapshot the coroutine had inherited and threw once that
                        // one had been left behind; giving each worker a snapshot
                        // of its own only moved the failure, since a dozen of them
                        // then applied against each other and conflicted. There is
                        // one thread this map may be written from and this is it.
                        withContext(Dispatchers.Main) { iconCache[key] = resolved }
                    }
                }
            }
        }
    }

    /** What one row may do, given the pack's contract and who owns the instance. */
    fun rulesFor(content: InstalledContent): ContentRowRules = contentRowRules(
        content         = content,
        manifestEntry   = entryFor(content),
        userOwned       = userOwns(content),
        optionalEnabled = optionalState[content.fileName],
    )

    /**
     * Whether this row is the player's to edit freely.
     *
     * A detached instance is all theirs. The mirror's sync owns its folder whole
     * and answers no, as it always has. Anything else goes by the record: a file
     * the pack placed is the pack's, a file beside it is not -- and an update
     * only ever touches what the record names, so editing the rest is safe.
     */
    private fun userOwns(content: InstalledContent): Boolean = when {
        isLocal  -> true
        isMirror -> false
        else     -> placed?.let { content.selectionKey() !in it } ?: false
    }

    /**
     * Whether the player may put their own files into this instance at all.
     *
     * The same reasoning one step up: what an update leaves alone is what the
     * record does not name, so a source that keeps one can be added to.
     */
    val canAddContent: Boolean get() = isLocal || (!isMirror && placed != null)

    fun iconFor(content: InstalledContent): ContentIconState? = iconCache[content.selectionKey()]

    /**
     * Flip one row. An optional mod on a tracked pack goes through the pack's
     * optional-content path -- persisted and relabelled on the launcher scope, so
     * navigating away mid-flip still reaches disk, and the choice survives a pack
     * update. A user-owned file is renamed on disk -- on the app's scope, for the
     * same reason. A required mod does neither.
     */
    fun toggle(content: InstalledContent, enabled: Boolean) {
        val rules = rulesFor(content)
        when {
            rules.optional -> toggleOptional(content.fileName, enabled)
            rules.showToggle -> writeScope.launch {
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
        publish(m, next)
    }

    /**
     * Hand a selection to the launcher. Whole-selection writes supersede each
     * other there, so a rapid pair reaches the record as one value and the re-seed
     * that follows agrees with what is on screen.
     */
    private fun publish(manifest: SmrtPackManifest, state: Map<String, Boolean>) {
        controller.setOptionalModsAsync(instance, manifest, OptionalContentRules.togglesFrom(manifest.mods, state))
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
            publish(m, next)
        }
        if (onDisk.isNotEmpty()) {
            writeScope.launch {
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
    fun addFiles(dialogSettings: FileKitDialogSettings) {
        writeScope.launch {
            val kind = filter.kind ?: ContentKind.Mod
            val extensions = if (kind == ContentKind.Mod) listOf("jar") else listOf("zip")
            val picked = pickFiles(
                type     = FileKitType.File(extensions = extensions),
                settings = dialogSettings,
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
        writeScope.launch {
            targets.forEach { manager.delete(instanceDir, it.kind, it.fileName) }
            if (single == null) clearSelection()
            rescan()
        }
    }

    // -- browse ---------------------------------------------------------------

    /**
     * Picks up whatever the project browser downloaded.
     *
     * Whether the browser is OPEN is not kept here. This holder is rebuilt on every
     * visit, so a reader who opened the browser, opened a project page from it and
     * came back landed in the content list instead of the search they left. The
     * flag lives beside the tab index now, which is saved for exactly that reason.
     */
    fun refreshAfterBrowse() {
        scope.launch { rescan() }
    }

    // -- updates --------------------------------------------------------------

    /**
     * Ask Modrinth what is newer for the files this instance may replace.
     *
     * Runs once when the tab opens and again on demand. Only the editable rows
     * are asked about: a mod the pack owns cannot be swapped from here, and
     * offering an update that the next pack sync would undo is worse than not
     * offering one.
     */
    suspend fun checkUpdates(force: Boolean = false) {
        val targets = updatable
        val mcVersion = instance.cachedManifest?.minecraftVersion.orEmpty()
        // Nothing to ask about, or nothing to ask WITH. An instance whose manifest
        // has not been read yet has no game version to match against, and saying
        // "everything is up to date" off the back of a question never asked is the
        // one answer here that would be a lie.
        if (targets.isEmpty()) {
            checked = true
            return
        }
        if (mcVersion.isBlank()) return
        checkingUpdates = true
        try {
            val outcome = updater.check(
                instanceDir = instanceDir,
                items       = targets,
                mcVersion   = mcVersion,
                loader      = loaderId(),
                force       = force,
            )
            // Whatever came back is worth keeping even when the round was not
            // complete; what the round could not answer is reported separately
            // rather than drawn as an answer.
            withContext(Dispatchers.Main) {
                updates = outcome.updates
                checkFailed = !outcome.complete
                checked = true
            }
        } catch (e: CancellationException) {
            // Leaving the tab cancels this. It is not a failed check, and telling
            // the reader it was would be a notice about their own navigation.
            throw e
        } catch (e: Exception) {
            // The previous answer stands: a check that could not run is not
            // evidence that the updates it found last time have gone away.
            withContext(Dispatchers.Main) { checkFailed = true }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { checkingUpdates = false }
        }
    }

    /**
     * Follow the batch this instance has running, if any.
     *
     * The run belongs to the app, not to the tab, so coming back to a tab left
     * mid-update finds the line where it actually is. A finished run stays until
     * the screen takes it down, which is what lets it report what failed.
     */
    suspend fun watchUpdateRun() {
        updater.runs.collect { all -> updateRun = all[updater.keyOf(instanceDir)] }
    }

    /**
     * How many updates the confirm gate is currently asking about, or 0 when it
     * is not open. The count is the question -- "update 52 projects?" -- so it is
     * what gets held rather than a bare boolean.
     */
    var pendingUpdateAll by mutableStateOf(0)
        private set

    fun requestUpdateAll() {
        pendingUpdateAll = liveUpdates.size
    }

    fun cancelUpdateAll() {
        pendingUpdateAll = 0
    }

    /** Install every update found, in one batch. */
    fun updateAll() {
        pendingUpdateAll = 0
        startUpdates(liveUpdates.values.toList())
    }

    /** Install the one update found for [content]. */
    fun update(content: InstalledContent) {
        liveUpdates[ContentRef(content.kind, content.fileName)]?.let { startUpdates(listOf(it)) }
    }

    private fun startUpdates(chosen: List<ModUpdate>) {
        if (chosen.isEmpty()) return
        val enabledBy = items.orEmpty().associate { ContentRef(it.kind, it.fileName) to rulesFor(it).effectiveEnabled }
        val targets = chosen.map { InstanceContentUpdater.Target(it, enabledBy[it.ref] ?: true) }
        updater.start(instance.id, instanceDir, instance.displayName, targets) {
            rescan()
            // The batch is done with the folder; re-asking is one request and it
            // is the only thing that can tell a swap that kept its file name from
            // an update still waiting.
            if (updater.runs.value[updater.keyOf(instanceDir)]?.finished == true) checkUpdates(force = true)
        }
    }

    // -- versions -------------------------------------------------------------

    /**
     * Open the version list for a row: every build Modrinth has of that project,
     * so a player can move to one that is not simply the newest -- back off a
     * broken release, or forward onto a beta on purpose.
     */
    fun openVersions(content: InstalledContent) {
        // The previous row's lookup is taken down first. Without that, closing one
        // row and opening another let the FIRST answer land under the second row's
        // name -- and picking a build from that list swapped the shown row's file
        // for a jar belonging to a different mod entirely.
        versionsJob?.cancel()
        versionsOf = content
        versionList = null
        versionsFailed = false
        versionsUnknown = false
        versionsJob = scope.launch {
            try {
                val project = resolveProject(content)
                if (project == null) {
                    versionsUnknown = true
                    return@launch
                }
                versionList = withContext(Dispatchers.IO) { modrinth.listVersions(project.id) }
            } catch (e: CancellationException) {
                // The window was closed, or the tab left. Neither is a failure to
                // report back into a window that is no longer there.
                throw e
            } catch (e: Exception) {
                versionsFailed = true
            }
        }
    }

    fun closeVersions() {
        versionsJob?.cancel()
        versionsJob = null
        versionsOf = null
        versionList = null
        versionsFailed = false
        versionsUnknown = false
    }

    /** The version id the open row is currently on, once its hash has been resolved. */
    fun installedVersionId(content: InstalledContent): String? =
        versionCache[content.selectionKey()]?.id

    /**
     * Put [version] where the row's file is. Same path as an update: fetch,
     * check against the published hash, swap, keeping the on/off state.
     */
    fun switchTo(content: InstalledContent, version: ModrinthVersion) {
        val ref = ContentRef(content.kind, content.fileName)
        val swap = version.swapFor(ref, content.version) ?: return
        switchingTo = version.id
        val started = updater.start(
            instance.id,
            instanceDir,
            instance.displayName,
            listOf(InstanceContentUpdater.Target(swap, rulesFor(content).effectiveEnabled)),
        ) {
            rescan()
            if (updater.runs.value[updater.keyOf(instanceDir)]?.finished == true) {
                // The batch runs on the app scope; what it changes on screen is
                // written where the screen reads it from.
                withContext(Dispatchers.Main) {
                    switchingTo = null
                    closeVersions()
                }
                checkUpdates(force = true)
            }
        }
        if (!started) switchingTo = null
    }

    private fun loaderId(): String =
        instance.cachedManifest?.loaderName
            ?.takeIf { it.isNotBlank() && !it.equals("vanilla", ignoreCase = true) }
            ?.lowercase()
            .orEmpty()

    // -- modrinth -------------------------------------------------------------

    /**
     * The item's Modrinth project by file hash, cached per item. Kind-agnostic
     * (mod, resource pack and shader all resolve the same way); null means
     * Modrinth does not index this file and callers fall back to embedded data.
     *
     * Throws when the lookup could not be made, which is a different answer from
     * null and must not be filed as one. A row asked about while the network was
     * down was otherwise recorded as absent from the catalogue for as long as the
     * tab lived, taking its version list and its page link down with it.
     */
    suspend fun resolveProject(content: InstalledContent): ModrinthProject? {
        val key = content.selectionKey()
        if (projectCache.containsKey(key)) return projectCache[key]
        val file = fileOf(content)
        val project = withContext(Dispatchers.IO) {
            val version = modrinth.versionByHash(sha1Of(file)) ?: return@withContext null
            withContext(Dispatchers.Main) { versionCache[key] = version }
            modrinth.resolveProject(version.projectId)
        }
        projectCache[key] = project
        return project
    }

    // -- internals ------------------------------------------------------------

    /** The manifest entry that governs [content], or null when the pack does not curate it. */
    private fun entryFor(content: InstalledContent): SmrtModEntry? =
        if (isMirror && content.kind == ContentKind.Mod) manifestMods[content.fileName] else null

    private fun fileOf(content: InstalledContent): Path = content.pathIn(instanceDir)

    /** Where this file's icon is filed for the life of the process. */
    private fun storeKey(content: InstalledContent): String =
        iconKey(instanceDir.normalize().toString(), content.selectionKey(), content.sizeBytes)

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
    val updater: InstanceContentUpdater = koinInject()
    val icons: ContentIconStore = koinInject()
    val scope = rememberCoroutineScope()
    val writeScope: CoroutineScope = koinInject()
    val state = remember(instance.id) {
        ContentTabState(
            initialInstance = instance,
            instanceDir  = paths.dataDir.resolve("instances").resolve(instance.instanceDirName),
            scanner      = scanner,
            manager      = InstanceContentManager(),
            mirrorClient = mirrorClient,
            controller   = controller,
            iconResolver = iconResolver,
            modrinth     = modrinth,
            updater      = updater,
            watch        = ContentFolderWatch(),
            icons        = icons,
            scope        = scope,
            writeScope   = writeScope,
        )
    }
    // Keyed on the record, not on its id: the tab keeps its scan, its icons and
    // its selection across a rewrite, and only re-reads what the rewrite changed.
    LaunchedEffect(state, instance) { state.adopt(instance) }
    return state
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
 * [userOwned] says the file is the player's rather than the pack's, which is the
 * caller's reading of the instance and not something derivable from the row.
 * [optionalEnabled] is the pack's optional-content state for the file, if any.
 *
 * Resource and shader packs are cosmetic rather than part of the pack contract,
 * so they stay user-managed even while the instance is tracked; mods do not.
 */
internal fun contentRowRules(
    content: InstalledContent,
    manifestEntry: SmrtModEntry?,
    userOwned: Boolean,
    optionalEnabled: Boolean?,
): ContentRowRules {
    val freeEdit = userOwned || content.kind != ContentKind.Mod
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

/**
 * The section chips, the filter panel and the search box, over the scan.
 *
 * [optionalNames] and [packKeys] come from the pack's manifest: a caller without
 * one -- a local pack, an offline fetch that came back empty -- passes empty sets,
 * and the axes that depend on them narrow to nothing rather than lying. That is
 * why the panel offers them only where the manifest actually has content.
 *
 * [packKeys] holds selection keys, not names: the pack ships mods under a filename
 * and assets under a path, and two folders can carry the same file name.
 */
internal fun filterContent(
    items: List<InstalledContent>,
    query: String,
    filter: ContentFilter,
    filters: ContentFilters = ContentFilters(),
    optionalNames: Set<String> = emptySet(),
    packKeys: Set<String> = emptySet(),
    effectiveOn: (InstalledContent) -> Boolean = { it.enabled },
): List<InstalledContent> = items.filter { c ->
    (filter.kind == null || c.kind == filter.kind) &&
        (!filters.optionalOnly || c.fileName in optionalNames) &&
        when (filters.owner) {
            ContentOwner.Any  -> true
            ContentOwner.Pack -> c.selectionKey() in packKeys
            ContentOwner.User -> c.selectionKey() !in packKeys
        } &&
        when (filters.status) {
            ContentStatus.Any      -> true
            // The row's own reading, not the file name on disk: an optional mod the
            // pack has not relabelled yet is off in the record and still `.jar` in
            // the folder, and the list must agree with the switch beside it.
            ContentStatus.Enabled  -> effectiveOn(c)
            ContentStatus.Disabled -> !effectiveOn(c)
        } &&
        (
            query.isBlank() ||
                c.displayName.contains(query, ignoreCase = true) ||
                c.fileName.contains(query, ignoreCase = true)
            )
}

/** How many of [picked] belong to the pack rather than to the user. */
internal fun lockedCount(
    picked: List<InstalledContent>,
    userOwns: (InstalledContent) -> Boolean,
    manifestMods: Map<String, SmrtModEntry>,
): Int = picked.count { c ->
    val freeEdit = userOwns(c) || c.kind != ContentKind.Mod
    val entry = if (c.kind == ContentKind.Mod) manifestMods[c.fileName] else null
    !freeEdit && (entry == null || entry.required)
}

/** Stable across a rescan: kind plus filename is what the row is keyed on too. */
internal fun InstalledContent.selectionKey(): String = contentKey(kind, fileName)

/** The same key from parts, for matching a manifest entry against a scanned row. */
internal fun contentKey(kind: ContentKind, fileName: String): String = "$kind:$fileName"

/**
 * The pack's recorded file paths as row selection keys, or null when there is no
 * record to read.
 *
 * A record names everything the pack placed, most of which -- configs, scripts,
 * the loader's own files -- has no row on this tab and is dropped here rather
 * than carried as keys nothing will ever match. Null is passed through instead of
 * collapsing to an empty set, because "the pack placed nothing here" and "nobody
 * knows what the pack placed" lead the rows to opposite conclusions.
 */
internal fun placedKeysFrom(paths: Set<String>?): Set<String>? =
    paths?.mapNotNullTo(mutableSetOf()) { path ->
        kindOfDest(path)?.let { contentKey(it, path.substringAfterLast('/')) }
    }

/** Which of this tab's folders a manifest asset lands in, or null when it lands elsewhere. */
internal fun kindOfDest(dest: String): ContentKind? =
    when (dest.substringBefore('/')) {
        ContentKind.Mod.folderName()          -> ContentKind.Mod
        ContentKind.ResourcePack.folderName() -> ContentKind.ResourcePack
        ContentKind.ShaderPack.folderName()   -> ContentKind.ShaderPack
        else                              -> null
    }

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
