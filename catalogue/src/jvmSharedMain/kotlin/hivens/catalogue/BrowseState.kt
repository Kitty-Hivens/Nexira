package hivens.catalogue

import hivens.catalogue.api.BROWSE_KINDS
import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.BrowseTarget
import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.FilterClear
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.FilterPick
import hivens.catalogue.api.PackOrigins
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTypes
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.TagSearch
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.PackPlacedContent
import hivens.launcher.instance.takesFromPlayer
import hivens.launcher.modrinth.acceptedLoaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("hivens.catalogue.Browse")

/**
 * What Browse is searching in one window, in what order, and where an install from it
 * goes, as [BrowseView]. One per window, made by the window's level and kept for its
 * life, so leaving Browse for a project page and coming back finds it as it was left,
 * and a second window browses on its own.
 *
 * The pack installs go into is read off disk here, the one place that owns that work,
 * off the thread that asked. Its folder and what it runs are kept beside the view for
 * the search, which installs into it.
 */
class BrowseState(
    /** Every pack source this build carries, in the order the rail lists them. */
    private val origins: List<String>,
    /** The pack with an id, as the library holds it. */
    private val packOf: suspend (String) -> PackInstance?,
    /** The folder every pack's folder sits in. */
    private val instancesDir: Path,
    private val scope: CoroutineScope,
    /** Where the pack is read off disk. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(origins: List<String>, packs: IPackRepository, instancesDir: Path, scope: CoroutineScope) :
        this(origins, { id -> packs.get(id) }, instancesDir, scope)

    private data class State(
        val kind: ProjectKind? = null,
        val origin: String? = null,
        val query: String = "",
        val sort: BrowseSort = BrowseSort.Relevance,
        val targetId: String? = null,
        val resolved: Resolved? = null,
        val aimingAt: String? = null,
        val chosen: Map<String, Set<SearchFilter>> = emptyMap(),
        val unlocked: Set<FilterField> = emptySet(),
        val hideInstalled: Boolean = false,
        val mirrorOwn: Boolean = true,
        val mirrorCommunity: Boolean = true,
    )

    /** The chosen pack read off disk: what a widget is shown of it, and where an install goes. */
    class Resolved(val view: BrowseTarget, val destination: ContentDestination.Pack)

    private var state = State()

    private val _view = MutableStateFlow(render(state))
    val view: StateFlow<BrowseView> = _view.asStateFlow()

    private var reading: Job? = null

    /** The resolved target, only while it is still the one chosen. */
    val target: Resolved? get() = synchronized(this) { state.resolved?.takeIf { it.view.packId == state.targetId } }

    private fun change(edit: (State) -> State) {
        synchronized(this) {
            state = edit(state)
            _view.value = render(state)
        }
    }

    fun setKind(kind: ProjectKind?) = change { it.copy(kind = kind) }

    fun setOrigin(origin: String) = change { it.copy(origin = origin) }

    fun setQuery(query: String) = change { it.copy(query = query) }

    fun setSort(sort: BrowseSort) = change { it.copy(sort = sort) }

    fun setHideInstalled(hide: Boolean) = change { it.copy(hideInstalled = hide) }

    fun setMirrorOwn(listed: Boolean) = change { it.copy(mirrorOwn = listed) }

    fun setMirrorCommunity(listed: Boolean) = change { it.copy(mirrorCommunity = listed) }

    fun toggleHideInstalled() = change { it.copy(hideInstalled = !it.hideInstalled) }

    fun toggleMirrorOwn() = change { it.copy(mirrorOwn = !it.mirrorOwn) }

    fun toggleMirrorCommunity() = change { it.copy(mirrorCommunity = !it.mirrorCommunity) }

    fun unlock(field: FilterField) = change { it.copy(unlocked = it.unlocked + field) }

    fun relock(field: FilterField) = change { it.copy(unlocked = it.unlocked - field) }

    /**
     * Installs into [packId], or into none. An unlocked filter was a decision about one
     * pack, not about the next, so a change of pack locks them again. The same pack
     * named again is read off disk again: a pack updated since to another game version
     * otherwise kept the earlier reading for every install from the list.
     */
    fun setTarget(packId: String?) {
        change { s -> if (packId != s.targetId) s.copy(targetId = packId, unlocked = emptySet()) else s }
        read(packId)
    }

    /**
     * Opens a browse into [packId]: the pack becomes the target, and once it is read the
     * search moves to a kind it takes. Each ask aims again, however many times the same
     * pack's browse was visited.
     */
    fun aimAt(packId: String) {
        change { s ->
            val next = if (packId != s.targetId) s.copy(targetId = packId, unlocked = emptySet()) else s
            next.copy(aimingAt = packId)
        }
        read(packId)
    }

    /** Asks for [pick]'s filter, or drops it when it is already chosen either way. */
    fun toggle(pick: FilterPick) = change { s ->
        val current = s.chosen[pick.type].orEmpty()
        val same = current.filter { it.field == pick.filter.field && it.value == pick.filter.value }
        val next = if (same.isNotEmpty()) current - same.toSet() else current + pick.filter.copy(excluded = false)
        s.copy(chosen = s.chosen + (pick.type to next))
    }

    /** Rules [pick]'s filter out, or drops it when it is already ruled out. */
    fun toggleExclude(pick: FilterPick) = change { s ->
        val current = s.chosen[pick.type].orEmpty()
        val same = current.filter { it.field == pick.filter.field && it.value == pick.filter.value }
        val next = if (same.any { it.excluded }) current - same.toSet() else current - same.toSet() + pick.filter.copy(excluded = true)
        s.copy(chosen = s.chosen + (pick.type to next))
    }

    fun remove(pick: FilterPick) = change { s -> s.copy(chosen = s.chosen + (pick.type to (s.chosen[pick.type].orEmpty() - pick.filter))) }

    fun clear(clear: FilterClear) = change { s ->
        val current = s.chosen[clear.type].orEmpty()
        val fields = clear.fields
        s.copy(chosen = s.chosen + (clear.type to if (fields == null) emptySet() else current.filterNot { it.field in fields }.toSet()))
    }

    /**
     * A fresh search for [search]'s type narrowed by its filters alone: nothing typed,
     * the type's other choices dropped, and its pack as the target. A field the pack
     * would decide is taken out of its hands, because the tag that was clicked is the
     * question.
     */
    fun searchFor(search: TagSearch) {
        val searched = BROWSE_TYPES[search.type] ?: if (search.type == ProjectTypes.MODPACK) null else return
        change { s ->
            val retargeted = if (search.packId != s.targetId) s.copy(targetId = search.packId, unlocked = emptySet()) else s
            val taken = search.filters.map { it.field }.filter { it in PACK_FIELDS && it !in retargeted.unlocked }.toSet()
            retargeted.copy(
                kind = searched,
                origin = if (searched == null) PackOrigins.MODRINTH else retargeted.origin,
                query = "",
                chosen = retargeted.chosen + (search.type to search.filters),
                unlocked = retargeted.unlocked + taken,
            )
        }
        read(search.packId)
    }

    /**
     * Everything a search for [type] of [kind] is narrowed by: the person's choices,
     * except where the pack has the field locked, and the pack's locked values.
     */
    fun searchFiltersFor(type: String, kind: ProjectKind?): Set<SearchFilter> = synchronized(this) {
        val locked = lockedFor(state, kind)
        val lockedFields = locked.mapTo(HashSet()) { it.field }
        state.chosen[type].orEmpty().filterNot { it.field in lockedFields }.toSet() + locked
    }

    /** Reads the target [packId] off disk, and moves the search to a kind it takes when Browse was aimed at it. */
    private fun read(packId: String?) {
        synchronized(this) {
            reading?.cancel()
            if (packId == null) {
                state = state.copy(resolved = null)
                _view.value = render(state)
                return
            }
            reading = scope.launch { readTarget(packId) }
        }
    }

    private suspend fun readTarget(id: String) {
        val pack = try {
            withContext(io) { packOf(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable is not gone. The choice stays and the rows install into no
            // pack until it can be read. The reading from before goes with it: kept, it
            // answered for a pack whose game version may have moved since.
            log.warn("reading the install target {} failed", id, e)
            change { s -> if (s.targetId == id) s.copy(resolved = null) else s }
            return
        }
        if (pack == null) {
            // Deleted while chosen. Nothing can go into it any more.
            change { s -> if (s.targetId == id) s.copy(targetId = null, resolved = null, unlocked = emptySet()) else s }
            return
        }
        val resolved = withContext(io) {
            val destination = ContentDestination.Pack.of(pack, instancesDir)
            Resolved(
                view = BrowseTarget(pack.id, pack.displayName, destination.mcVersion, destination.loader, kindsFor(destination)),
                destination = destination,
            )
        }
        change { s ->
            if (s.targetId != id) return@change s
            val aimed = s.aimingAt == id
            val kind = if (aimed && !resolved.view.takes(s.kind)) resolved.view.kinds.firstOrNull() ?: s.kind else s.kind
            s.copy(resolved = resolved, kind = kind, aimingAt = if (aimed) null else s.aimingAt)
        }
    }

    private fun render(s: State): BrowseView {
        val target = s.resolved?.takeIf { it.view.packId == s.targetId }
        return BrowseView(
            kind = s.kind,
            pickedOrigin = s.origin,
            origin = activeOrigin(s.origin, origins),
            origins = origins,
            query = s.query,
            sort = s.sort,
            targetId = s.targetId,
            target = target?.view,
            chosen = s.chosen,
            unlocked = s.unlocked,
            locked = lockedFor(s, s.kind),
            hideInstalled = s.hideInstalled,
            mirrorOwn = s.mirrorOwn,
            mirrorCommunity = s.mirrorCommunity,
        )
    }

    /**
     * What the target pack decides for [kind] while the person has not unlocked it: its
     * game version, and for mods the loaders it runs. Empty without a target that takes
     * the kind, because a pack that does not take it has nothing to narrow.
     */
    private fun lockedFor(s: State, kind: ProjectKind?): List<SearchFilter> {
        val t = s.resolved?.takeIf { it.view.packId == s.targetId && it.view.takes(kind) }?.destination ?: return emptyList()
        return buildList {
            if (FilterField.GameVersion !in s.unlocked && t.mcVersion.isNotBlank()) add(SearchFilter(FilterField.GameVersion, t.mcVersion))
            if (kind == ProjectKind.Mod && FilterField.Loader !in s.unlocked && t.loader.isNotBlank()) {
                acceptedLoaders(t.loader, t.mcVersion).forEach { add(SearchFilter(FilterField.Loader, it)) }
            }
        }
    }

    companion object {
        /** The fields a target pack decides. */
        private val PACK_FIELDS = setOf(FilterField.GameVersion, FilterField.Loader)

        /** The project types Browse searches, with the kind it lists each under. */
        private val BROWSE_TYPES: Map<String, ProjectKind?> = BROWSE_KINDS.filterNotNull().associateBy { it.projectType }

        /** The source packs are listed from: the one picked, else the first registered. */
        fun activeOrigin(picked: String?, origins: List<String>): String =
            picked?.takeIf { it in origins } ?: origins.firstOrNull() ?: PackOrigins.MIRROR

        /**
         * The kinds a pack takes from the player, in the order the rail lists them. A
         * mirror pack never takes mods, and a pack with no loader runs none, so a row
         * offering to install one there would offer something that cannot happen.
         */
        fun kindsFor(target: ContentDestination.Pack): List<ProjectKind> {
            val keepsRecord = Files.isDirectory(target.dir) && PackPlacedContent.paths(target.dir) != null
            return BROWSE_KINDS.filterNotNull().filter { kind ->
                takesFromPlayer(target.origin, kind.toContentKind(), keepsRecord) &&
                    (kind != ProjectKind.Mod || acceptedLoaders(target.loader, target.mcVersion).isNotEmpty())
            }
        }
    }
}
