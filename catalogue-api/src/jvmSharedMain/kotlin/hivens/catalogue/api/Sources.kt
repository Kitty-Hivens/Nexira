package hivens.catalogue.api

import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A source of packs: what the catalogue lists packs from, shows a pack's page of and
 * reads a pack's builds from. One per source, brought by the module that knows it.
 *
 * [id] is the source's id, the string every pack record from it carries, so it never
 * changes: the library finds a pack's source by it, and a pack whose source module is
 * not brought in keeps it and says that its source is missing. The catalogue refuses an
 * id the core keeps for itself (`Local`, `Smartycraft`, `Unknown`), one a source compiled
 * into the launcher declares, and one another module already brought. [label] is the source's
 * name and every label in [filters] a key, all looked up in the words of the module that
 * brings the source.
 *
 * Carried like any other contribution: a jar lists it as a service under this
 * interface's name, `META-INF/services/hivens.catalogue.api.PackSourceContribution`, and names
 * [CatalogueApi.ID] in its manifest's `Nexira-Module-Requires`, without which it cannot
 * see this interface. A module compiled into the launcher lists it among its
 * contributions. The catalogue takes every one the modules brought in carry, and
 * nothing else registers a source.
 */
interface PackSourceContribution : ModuleContribution {
    val icon: SourceIcon? get() = null

    /**
     * Whether [PackListing.search] honours its page. False for a source that answers
     * with its whole listing whatever page is asked, where one answer is the entire list
     * and a later one replaces it rather than adding to it.
     */
    val paged: Boolean get() = true

    /** The ways the source splits its own list, which Browse offers while it lists this source. */
    val filters: List<SourceFilter> get() = emptyList()

    /**
     * The catalogue project type the source's packs are searched as ([ProjectTypes]),
     * where a project search lists them ([ProjectSearchContribution]) and its filters,
     * sorts and tags apply. Null lists them through [PackListing.search].
     */
    val packType: String? get() = null

    /**
     * What answers for the source, made once for the app. [services] are the app level's
     * services as the module that brings the source sees them: the services modules
     * declared, the host's own types the host shows every module, and that module's own
     * settings domains. A source that needs more of the launcher than that declares a
     * service of its own through its key contribution, made where its module is trusted
     * with more, and reads it here. A module loaded again is opened again, and whatever
     * read the old listing reads the new one.
     */
    fun open(services: LevelServices): PackListing
}

/**
 * The pack sources the catalogue lists from, as a module the host trusts installs from
 * them: each opened once, the one the catalogue reads, and only those it took. A source
 * it refused, for a reserved id, an id another module already brought or a declaration
 * that could not be read, is not here either. The catalogue module keeps it as a host
 * service, so a module that is not trusted has none.
 */
interface PackSources {
    /** What answers for the source with [id], or null where the catalogue lists no such source. */
    fun listing(id: String): PackListing?
}

/** What a [PackSourceContribution] answers. Every answer is in the catalogue's plain values. */
interface PackListing {
    /**
     * One page of the source's packs for [query], [page] counted from zero. A source
     * with no query of its own filters its listing. A blank [query] lists everything.
     */
    suspend fun search(query: String, page: Int = 0): List<CatalogueCard>

    /**
     * [search] as a stream: a kept answer at once and the fresh one behind it, or, for a
     * source that is watched, every change as it comes. The catalogue collects it only
     * while somebody is shown the list. The default is the single answer.
     */
    fun searchStream(query: String, page: Int = 0): Flow<List<CatalogueCard>> = flow { emit(search(query, page)) }

    /** A pack's page, with every build that can be installed. */
    suspend fun details(packId: String): PackDetails

    /** [details] as a stream, for the reason [searchStream] is one. The default is the single answer. */
    fun detailsStream(packId: String): Flow<PackDetails> = flow { emit(details(packId)) }

    /** A pack's builds, newest first where the source orders them. */
    suspend fun versions(packId: String): List<ProjectBuild>
}

/**
 * A source of projects: mods, resource packs, shaders and whatever else it files by a
 * catalogue project type, searched with the catalogue's filters ([SearchFilter]) and
 * sorts ([BrowseSort]), and the lists those filters are built from ([CatalogueTags]).
 * Browse searches a type of project, packs of a [PackSourceContribution.packType]
 * among them, in the first project source brought in that searches that type.
 *
 * Carried like [PackSourceContribution], under this interface's name.
 */
interface ProjectSearchContribution : ModuleContribution {
    /** The catalogue project types it searches ([ProjectTypes]). */
    val types: Set<String>

    /** What answers for the source, made once for the app, see [PackSourceContribution.open]. */
    fun open(services: LevelServices): ProjectSearcher
}

/** What a [ProjectSearchContribution] answers. */
interface ProjectSearcher {
    /** One page of projects of [type] for [query], narrowed by [filters], from [offset] in [sort]'s order. */
    suspend fun search(type: String, query: String, filters: Set<SearchFilter>, offset: Int, limit: Int, sort: BrowseSort): List<ProjectHit>

    /** The game versions, categories and loaders the filters are built from. */
    suspend fun tags(): CatalogueTags

    /** The game versions alone, newest first, for folding a version list. The default reads every list. */
    suspend fun gameVersions(): List<GameVersionTag> = tags().gameVersions
}
