package hivens.ui.screens.browse

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.launcher.instance.ContentKind
import hivens.launcher.modrinth.FilterField
import hivens.launcher.modrinth.SearchFilter
import hivens.launcher.modrinth.acceptedLoaders
import hivens.ui.i18n.AppStrings
import hivens.ui.screens.mod.ModTarget

/** Every kind Browse searches, in the order the rail lists them. Null stands for packs. */
internal val BROWSE_KINDS: List<ContentKind?> =
    listOf(null, ContentKind.Mod, ContentKind.ResourcePack, ContentKind.ShaderPack)

internal fun kindLabel(kind: ContentKind?, s: AppStrings): String = when (kind) {
    null -> s.browseKindPacks
    ContentKind.Mod -> s.contentFilterMods
    ContentKind.ResourcePack -> s.contentFilterResourcePacks
    ContentKind.ShaderPack -> s.contentFilterShaderPacks
}

/** The orders the catalogue sorts a project search by, as it names them. */
enum class BrowseSort(val index: String) {
    Relevance("relevance"),
    Downloads("downloads"),
    Follows("follows"),
    Newest("newest"),
    Updated("updated"),
}

internal fun BrowseSort.label(s: AppStrings): String = when (this) {
    BrowseSort.Relevance -> s.browseSortRelevance
    BrowseSort.Downloads -> s.browseSortDownloads
    BrowseSort.Follows -> s.browseSortFollows
    BrowseSort.Newest -> s.browseSortNewest
    BrowseSort.Updated -> s.browseSortUpdated
}

/**
 * What Browse is searching, in what order, and where an install from it goes.
 *
 * Shared between the screen's own widgets and the right rail's browse family,
 * which are different surfaces with no composition in common: the rail is where
 * the reader chooses, the centre is where the answer is drawn. Neither reaches for
 * the other. Both read and write this, the way the project page and its rail meet
 * at [hivens.ui.screens.mod.OpenProjectState].
 *
 * Kept for the life of the process, so leaving Browse for a project page and
 * coming back finds it as it was left.
 */
@Stable
class BrowseController {
    /** What is searched. Null stands for packs. */
    var kind by mutableStateOf<ContentKind?>(null)

    /** The catalogue packs come from. Null until one is picked, then the first registered one is meant. */
    var origin by mutableStateOf<PackOrigin?>(null)

    var query by mutableStateOf("")

    var sort by mutableStateOf(BrowseSort.Relevance)

    /**
     * The pack installs go into, by instance id. Null means none is chosen, and a
     * result then opens its page rather than offering an install that would have to
     * ask where.
     */
    var targetId: String?
        get() = chosenTarget
        set(value) {
            // An unlocked filter was a decision about one pack, not about the next.
            if (value != chosenTarget) unlocked.clear()
            chosenTarget = value
        }

    private var chosenTarget by mutableStateOf<String?>(null)

    /**
     * A pack Browse was just asked to install into, whose kind is still to be moved
     * to one the pack takes. Set by [aimAt] and spent by the screen once the pack is
     * read, so a return to the same screen from a project page keeps whatever kind
     * the reader picked since.
     */
    internal var aimingAt by mutableStateOf<String?>(null)

    /**
     * Opens a browse into [packId]: the pack becomes the target, and once it is read
     * the search moves to a kind it takes. Called where the reader asked for it, so
     * each ask aims again, however many times the same pack's browse was visited.
     */
    fun aimAt(packId: String) {
        targetId = packId
        aimingAt = packId
    }

    /**
     * [targetId] read off disk: the pack's folder and the kinds it takes.
     *
     * Resolved by the screen, which is the one place that owns the work of reading
     * a pack, and published here for the rail to read. Held separately from the id
     * because the read is off the UI thread: the rail names the chosen pack at once
     * and says what it takes a moment later.
     */
    internal var resolved by mutableStateOf<BrowseTarget?>(null)

    /** The resolved target, only while it is still the one chosen. */
    internal val target: BrowseTarget?
        get() = resolved?.takeIf { it.destination.pack.id == targetId }

    /**
     * What the reader chose in the filter rail, per catalogue project type.
     *
     * Per type, because a shader's features are not a mod's categories, and coming
     * back to mods after a look at shaders should find the mod filters as they were.
     */
    private val chosen = mutableStateMapOf<String, Set<SearchFilter>>()

    fun chosenFor(type: String): Set<SearchFilter> = chosen[type].orEmpty()

    /** Asks for [filter], or drops it when it is already chosen either way. */
    fun toggle(type: String, filter: SearchFilter) {
        val current = chosenFor(type)
        val same = current.filter { it.field == filter.field && it.value == filter.value }
        chosen[type] = if (same.isNotEmpty()) current - same.toSet() else current + filter.copy(excluded = false)
    }

    /** Rules [filter] out, or drops it when it is already ruled out. */
    fun toggleExclude(type: String, filter: SearchFilter) {
        val current = chosenFor(type)
        val same = current.filter { it.field == filter.field && it.value == filter.value }
        chosen[type] = if (same.any { it.excluded }) current - same.toSet() else current - same.toSet() + filter.copy(excluded = true)
    }

    fun remove(type: String, filter: SearchFilter) {
        chosen[type] = chosenFor(type) - filter
    }

    /** Drops every choice for [type], or only those of [fields]. */
    fun clear(type: String, fields: Set<FilterField>? = null) {
        chosen[type] = if (fields == null) emptySet() else chosenFor(type).filterNot { it.field in fields }.toSet()
    }

    /**
     * A fresh search for [type] narrowed by [filters] alone, the way a tag on a
     * project page asks for one: nothing typed, the type's other choices dropped, and
     * [packId] as the pack installs go into. A field the pack would decide is taken
     * out of its hands, because the tag that was clicked is the question.
     */
    fun searchFor(type: String, filters: Set<SearchFilter>, packId: String?) {
        if (type !in BROWSE_TYPES) return
        val searched = BROWSE_TYPES[type]
        kind = searched
        if (searched == null) origin = PackOrigin.Modrinth
        query = ""
        targetId = packId
        chosen[type] = filters
        filters.map { it.field }.filter { it in PACK_FIELDS && it !in unlocked }.distinct().forEach { unlocked += it }
    }

    /** Leaves out what the target pack already holds. */
    var hideInstalled by mutableStateOf(false)

    /** Lists the mirror's own packs. See [shows]. */
    var mirrorOwn by mutableStateOf(true)

    /** Lists the packs the mirror's community built. See [shows]. */
    var mirrorCommunity by mutableStateOf(true)

    /**
     * Whether a pack is listed under the reader's choice of who built it. The choice
     * is the mirror's, the one source that lists a community beside its own packs,
     * so a pack from any other source is always listed.
     */
    internal fun shows(pack: CataloguePack): Boolean = when {
        pack.origin != PackOrigin.Mirror -> true
        pack.community -> mirrorCommunity
        else -> mirrorOwn
    }

    /**
     * Fields the reader has taken out of the target pack's hands. Cleared whenever
     * the target changes, because an unlocked game version is a decision about one
     * pack and not about the next.
     */
    internal val unlocked = mutableStateListOf<FilterField>()

    /**
     * What the target pack decides for [kind] while the reader has not unlocked it:
     * its game version, and for mods the loaders it runs. Empty without a target that
     * takes the kind, because a pack that does not take it has nothing to narrow.
     */
    internal fun lockedFor(kind: ContentKind?): List<SearchFilter> {
        val t = target?.takeIf { it.takes(kind) }?.destination?.target ?: return emptyList()
        return buildList {
            if (FilterField.GameVersion !in unlocked && t.mcVersion.isNotBlank()) {
                add(SearchFilter(FilterField.GameVersion, t.mcVersion))
            }
            if (kind == ContentKind.Mod && FilterField.Loader !in unlocked && t.loader.isNotBlank()) {
                acceptedLoaders(t.loader, t.mcVersion).forEach { add(SearchFilter(FilterField.Loader, it)) }
            }
        }
    }

    /** The fields [lockedFor] decides, whatever values it gave them. */
    internal fun lockedFieldsFor(kind: ContentKind?): Set<FilterField> =
        lockedFor(kind).mapTo(HashSet()) { it.field }

    /**
     * Everything a search for [type] is narrowed by: the reader's choices, except
     * where the pack has the field locked, and the pack's locked values.
     */
    internal fun searchFiltersFor(type: String, kind: ContentKind?): Set<SearchFilter> {
        val locked = lockedFor(kind)
        val lockedFields = locked.mapTo(HashSet()) { it.field }
        return chosenFor(type).filterNot { it.field in lockedFields }.toSet() + locked
    }
}

/** The fields a target pack decides, see [BrowseController.lockedFor]. */
private val PACK_FIELDS = setOf(FilterField.GameVersion, FilterField.Loader)

/** The pack installs go into, with what it runs and the kinds it takes. */
internal class BrowseTarget(val destination: BrowseDestination, val kinds: List<ContentKind>) {
    fun takes(kind: ContentKind?): Boolean = kind != null && kind in kinds
}

/** How the screen's widgets leave it: to a catalogue pack's page, or to a project's. */
class BrowseContext(
    val onOpenPack: (CataloguePack) -> Unit,
    val onOpenProject: (ModTarget) -> Unit,
)

val LocalBrowseContext: ProvidableCompositionLocal<BrowseContext> =
    staticCompositionLocalOf { error("LocalBrowseContext not provided -- mount inside BrowseScreen") }

/** What the editor hands Browse's widgets when they are drawn somewhere else. */
internal val STUB_BROWSE = BrowseContext(onOpenPack = {}, onOpenProject = {})
