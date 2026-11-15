package hivens.catalogue.api

/**
 * What lands in a pack's content folder, and so what Browse searches besides packs.
 * Browse names packs themselves as no kind at all, a null where a kind is asked for.
 */
enum class ProjectKind {
    Mod,
    ResourcePack,
    ShaderPack,
    ;

    /** The catalogue's project type for this kind. */
    val projectType: String
        get() = when (this) {
            Mod -> ProjectTypes.MOD
            ResourcePack -> ProjectTypes.RESOURCE_PACK
            ShaderPack -> ProjectTypes.SHADER
        }
}

/** Every kind Browse searches, in the order a rail lists them. Null stands for packs. */
val BROWSE_KINDS: List<ProjectKind?> = listOf(null, ProjectKind.Mod, ProjectKind.ResourcePack, ProjectKind.ShaderPack)

/** The catalogue's project types, as it spells them in a search and in a URL. */
object ProjectTypes {
    const val MOD: String = "mod"
    const val RESOURCE_PACK: String = "resourcepack"
    const val SHADER: String = "shader"
    const val MODPACK: String = "modpack"
}

/** The pack sources a catalogue lists packs from, by the name the packs API gives them. */
object PackOrigins {
    const val MIRROR: String = "mirror"
    const val MODRINTH: String = "modrinth"
    const val SMARTYCRAFT: String = "smartycraft"
    const val LOCAL: String = "local"
    const val UNKNOWN: String = "unknown"
}

/**
 * The catalogue project type a search for [kind] from [origin] is for: the kind's own,
 * or modpacks while packs are listed from the catalogue. Null where packs are listed
 * from a source that is not the catalogue, which has no filters to offer.
 */
fun catalogueTypeOf(kind: ProjectKind?, origin: String): String? = when {
    kind != null -> kind.projectType
    origin == PackOrigins.MODRINTH -> ProjectTypes.MODPACK
    else -> null
}

/**
 * What a catalogue filter narrows. Each one is a field of the catalogue's search
 * index, and how several chosen values of it combine is the field's own rule.
 */
enum class FilterField {
    /** A game version. Several are any of them. */
    GameVersion,

    /** A loader a build is published for. Several are any of them. */
    Loader,

    /** A category. Several are all of them, the way the catalogue's own sidebar reads. */
    Category,

    /** A resource pack's resolution. Several are any of them, since a pack has one. */
    Resolution,

    /** Where the project runs, [ENV_CLIENT] or [ENV_SERVER]. */
    Environment,

    /** Whether the licence is an open one. The value is ignored. */
    OpenSource,

    /** Something the author declares about the project, like telemetry. Only ever excluded. */
    Disclosure,

    /** A project by id. Only ever excluded, which is how what a pack already has is hidden. */
    Project,
}

/** One chosen value. [excluded] keeps it out rather than asking for it. */
data class SearchFilter(val field: FilterField, val value: String, val excluded: Boolean = false)

/** The environment a [FilterField.Environment] filter asks for: the client. */
const val ENV_CLIENT: String = "client"

/** The environment a [FilterField.Environment] filter asks for: the server. */
const val ENV_SERVER: String = "server"

/** The orders the catalogue sorts a project search by, with the name it gives each. */
enum class BrowseSort(val index: String) {
    Relevance("relevance"),
    Downloads("downloads"),
    Follows("follows"),
    Newest("newest"),
    Updated("updated"),
}

/**
 * The pack Browse installs into, read off disk: what it runs and the kinds it takes
 * from the player. A mirror pack takes no mods, and a pack with no loader runs none.
 */
data class BrowseTarget(
    val packId: String,
    val name: String,
    /** The Minecraft version the pack runs, blank where it has not said. */
    val mcVersion: String,
    /** The pack's loader as the catalogue names loaders, blank for none. */
    val loader: String,
    val kinds: List<ProjectKind>,
) {
    /** Whether an install of [kind] can go into this pack. Packs themselves never can. */
    fun takes(kind: ProjectKind?): Boolean = kind != null && kind in kinds
}

/**
 * What Browse is searching in one window, in what order, and where an install from it
 * goes. The search box, the results, the filter rail and the pack header all read this
 * and change it through the browse commands, so none of them needs another.
 */
data class BrowseView(
    /** What is searched. Null stands for packs. */
    val kind: ProjectKind? = null,
    /** The source packs come from as the person picked it, null until they did. */
    val pickedOrigin: String? = null,
    /** The source packs are listed from: the one picked, else the first one this build carries. */
    val origin: String = PackOrigins.MIRROR,
    /** Every pack source this build carries, in the order the rail lists them. */
    val origins: List<String> = emptyList(),
    val query: String = "",
    val sort: BrowseSort = BrowseSort.Relevance,
    /** The pack installs go into, by id. Null means none is chosen, and a result then opens its page. */
    val targetId: String? = null,
    /** [targetId] read off disk, present only while it is still the one chosen. */
    val target: BrowseTarget? = null,
    /**
     * What the person chose in the filter rail, per catalogue project type, because a
     * shader's features are not a mod's categories.
     */
    val chosen: Map<String, Set<SearchFilter>> = emptyMap(),
    /** Fields the person took out of the target pack's hands. Cleared whenever the target changes. */
    val unlocked: Set<FilterField> = emptySet(),
    /**
     * What the target pack decides for [kind] while the person has not unlocked it: its
     * game version, and for mods the loaders it runs. Empty without a target that takes the kind.
     */
    val locked: List<SearchFilter> = emptyList(),
    /** Leaves out what the target pack already holds. */
    val hideInstalled: Boolean = false,
    /** Lists the mirror's own packs. */
    val mirrorOwn: Boolean = true,
    /** Lists the packs the mirror's community built. */
    val mirrorCommunity: Boolean = true,
) {
    /** The catalogue project type searched now, see [catalogueTypeOf]. */
    val type: String? get() = catalogueTypeOf(kind, origin)

    /** What the person chose for [type]. */
    fun chosenFor(type: String): Set<SearchFilter> = chosen[type].orEmpty()

    /**
     * Whether [card] is listed under the person's choice of who built it. The choice
     * is the mirror's, the one source that lists a community beside its own packs, so a
     * pack from any other source is always listed.
     */
    fun shows(card: CatalogueCard): Boolean = when {
        card.origin != PackOrigins.MIRROR -> true
        card.community -> mirrorCommunity
        else -> mirrorOwn
    }
}

/** A filter of a project type, for the commands that choose, exclude or drop one. */
data class FilterPick(val type: String, val filter: SearchFilter)

/** Drops every choice for [type], or only those of [fields]. */
data class FilterClear(val type: String, val fields: Set<FilterField>? = null)

/**
 * A fresh search for [type] narrowed by [filters] alone, the way a tag on a project
 * page asks for one: nothing typed, the type's other choices dropped, and [packId] as
 * the pack installs go into. A field the pack would decide is taken out of its hands.
 */
data class TagSearch(val type: String, val filters: Set<SearchFilter>, val packId: String? = null)

/** A pack as a catalogue lists it, whichever source lists it. */
data class CatalogueCard(
    /** The source's name, see [PackOrigins]. */
    val origin: String,
    /** The source's own id for the pack. */
    val id: String,
    val title: String,
    val tagline: String,
    val iconUrl: String? = null,
    /** A wide picture for the card, or null where the source has none. */
    val bannerUrl: String? = null,
    val tags: List<String> = emptyList(),
    /** The Minecraft version the source puts on its card, where it puts one. */
    val mcVersion: String? = null,
    /** Built by a member of the source's community rather than by the source itself. */
    val community: Boolean = false,
    /** Who built it, for a byline. Null where the source itself did. */
    val author: String? = null,
) {
    /** The pack's identity across sources, since two sources may both have a pack "1". */
    val key: String get() = "$origin:$id"
}

/** One project the catalogue found. */
data class ProjectHit(
    val projectId: String,
    val slug: String = "",
    val title: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    val featuredGallery: String? = null,
    val gallery: List<String> = emptyList(),
    val author: String = "",
    val downloads: Long = 0,
    val follows: Long = 0,
    /** RFC 3339, blank where the catalogue did not say. */
    val dateModified: String = "",
    val dateCreated: String = "",
    val displayCategories: List<String> = emptyList(),
)

/**
 * One search as it was asked: what of which source or project type, typed how, in
 * what order, narrowed how, for which pack. Two equal questions are one search, which
 * is what a list position and a page kept from before belong to.
 */
data class SearchQuestion(
    val kind: ProjectKind?,
    val origin: String,
    /** The catalogue project type, null for packs listed from a source that is not the catalogue. */
    val type: String?,
    val query: String,
    val sort: BrowseSort,
    val filters: Set<SearchFilter>,
    /** The pack installs go into, where one takes [kind]. */
    val packId: String?,
    /** Whether what that pack holds is left out. */
    val hiding: Boolean,
)

/** Where a search stands. */
sealed interface SearchStatus {
    data object Loading : SearchStatus

    data object Loaded : SearchStatus

    /** The source answered and listed nothing. */
    data object Empty : SearchStatus

    /** The search could not be run. [message] is the source's own words, null where there were none. */
    data class Failed(val message: String? = null) : SearchStatus
}

/** Where a list was scrolled to: its first visible row and how far into it. */
data class ScrollMark(val index: Int = 0, val offset: Int = 0)

/** Where the person left the list that answers [question], for a return to it. */
data class ResultsScroll(val question: SearchQuestion, val mark: ScrollMark)

/**
 * What the window's search found, and the installs started from it.
 *
 * Packs land in [packs] and projects in [projects], by which [question] asked. A pack
 * list is kept whole, so the person's choice of who built a pack is applied by the
 * reader with [BrowseView.shows] and taking it back asks the source nothing.
 */
data class SearchResults(
    /** The search these results answer, null before the first one was asked. */
    val question: SearchQuestion? = null,
    val status: SearchStatus = SearchStatus.Loading,
    val packs: List<CatalogueCard> = emptyList(),
    val projects: List<ProjectHit> = emptyList(),
    /** No further page is to be had. */
    val endReached: Boolean = false,
    /** Where a list showing these results starts, the place the person left it last time. */
    val scroll: ScrollMark = ScrollMark(),
    /** Counts each time a fresh first page replaced what was shown, which a reader answers by going to the top. */
    val toTop: Int = 0,
    /** Projects the target pack already holds. */
    val present: Set<String> = emptySet(),
    /** Projects being installed into the target pack now. */
    val working: Set<String> = emptySet(),
    /** Why the last install of a project did not land, by project. */
    val problems: Map<String, InstallProblem> = emptyMap(),
    /** How many required dependencies an install that landed went without, by project. */
    val missing: Map<String, Int> = emptyMap(),
)

/** One Minecraft version as the catalogue lists it. */
data class GameVersionTag(
    val version: String,
    /** `release`, `snapshot`, `alpha` or `beta`. */
    val versionType: String = "",
    val major: Boolean = false,
    /** RFC 3339, null where the catalogue did not date it. */
    val date: String? = null,
) {
    companion object {
        const val RELEASE: String = "release"
        const val SNAPSHOT: String = "snapshot"
    }
}

/** One category, the group the catalogue files it under, and its mark as SVG. */
data class CategoryTag(
    val name: String,
    val projectType: String = "",
    val header: String = "",
    val icon: String = "",
)

/** One loader and the project types it publishes. */
data class LoaderTag(
    val name: String,
    val supportedProjectTypes: List<String> = emptyList(),
    val icon: String = "",
)

/**
 * The catalogue's own lists the filters are built from, read once a run: its game
 * versions newest first, its categories and its loaders.
 */
data class CatalogueTags(
    val gameVersions: List<GameVersionTag> = emptyList(),
    val categories: List<CategoryTag> = emptyList(),
    val loaders: List<LoaderTag> = emptyList(),
)
