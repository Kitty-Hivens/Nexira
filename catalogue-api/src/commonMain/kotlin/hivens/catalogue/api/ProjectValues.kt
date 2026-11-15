package hivens.catalogue.api

/**
 * What a project page was opened on.
 *
 * Two cases rather than one nullable id, because only one of them can always be
 * answered. A catalogue entry is addressed by its id and exists whether or not
 * anything is installed. A file in a pack is addressed by the file, and the catalogue
 * may never have indexed it.
 */
sealed interface ProjectTarget {
    /**
     * A catalogue entry, installed or not. [intoInstanceId] is the pack the person was
     * browsing from, which is what lets the page install: a project id says what to
     * install and nothing about where.
     */
    data class Catalogue(val projectId: String, val intoInstanceId: String? = null) : ProjectTarget

    /** A file inside a pack, which the catalogue may or may not know. */
    data class Installed(val instanceId: String, val kind: ProjectKind, val fileName: String) : ProjectTarget

    /** Identity for a history entry and for state kept per page. */
    val key: String
        get() = when (this) {
            is Catalogue -> "catalogue:$projectId:${intoInstanceId.orEmpty()}"
            is Installed -> "installed:$instanceId:${kind.name}:$fileName"
        }

    /** The pack behind the page: the one the person came from, or the one the file sits in. */
    val packId: String?
        get() = when (this) {
            is Catalogue -> intoInstanceId
            is Installed -> instanceId
        }
}

/**
 * Where a page's facts came from. [Mirror] is the mirror's registry answering for a
 * file the catalogue does not carry. [Local] is the archive on disk, which answers some
 * questions better than the catalogue and others not at all.
 */
enum class ProjectSource { Catalogue, Mirror, Local }

/** One of the author's own addresses, named by what it is rather than by its host. */
enum class ProjectLinkKind { Issues, Source, Wiki, Discord, Donate, Page }

/** [label] is the destination's own name where it has one: Patreon and Ko-fi rather than "donate" twice. */
data class ProjectLink(val kind: ProjectLinkKind, val url: String, val label: String? = null)

/** One person credited on a project, and what the project calls their part in it. */
data class ProjectCreator(
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
    val owner: Boolean = false,
)

/**
 * One game version chip and the versions behind it. The label is a handle for a set:
 * `1.21.x` stands for every minor of that major a project supports.
 */
data class GameVersionGroup(val label: String, val versions: List<String>)

/** Something the author declares a project does, like telemetry or advertisements. */
data class Disclosure(
    val type: String,
    val note: String? = null,
    val consent: String? = null,
    val features: List<String> = emptyList(),
    val dataCollected: List<String> = emptyList(),
    val sources: List<DisclosureSource> = emptyList(),
) {
    companion object {
        const val TELEMETRY: String = "telemetry"
        const val ADVERTISEMENTS: String = "advertisements"
        const val PAID_FEATURES: String = "paid_features"
        const val AI_CONTENT: String = "ai_content"
        const val AI_FUNCTIONALITY: String = "ai_functionality"
        const val SYSTEM_INTERACTIONS: String = "system_interactions"
        const val EPILEPSY_TRIGGERS: String = "epilepsy_triggers"
        const val DERIVATIVE_WORK: String = "derivative_work"
    }
}

/** Where a declared thing comes from, with an address where the author gave one. */
data class DisclosureSource(val label: String = "", val link: String? = null, val note: String? = null)

/** One picture of a project or a pack, at a size to show in a strip and at its full size. */
data class GalleryItem(
    val full: String,
    val thumb: String,
    val title: String? = null,
    val description: String? = null,
    /** Whether the source leads with it. */
    val featured: Boolean = false,
)

/**
 * What the right rail knows about the project or pack the person is looking at, as
 * the page open in the window published it.
 *
 * A field nothing could fill stays empty or null, which the rail draws as not known
 * rather than as an answer. Times are RFC 3339, for the reader to put into words.
 */
data class OpenProject(
    /** Which page this describes, as its target's key, so a reader can tell it is about what they ask. */
    val targetKey: String,
    val title: String,
    val slug: String,
    val source: ProjectSource,
    /** The page has not heard back yet, which is not an answer that came back empty. */
    val pending: Boolean = false,
    /** The catalogue's project type, so a tag can open a search of the same kind. Null for a file it does not know. */
    val projectType: String? = null,
    /** The pack behind the page, so a search opened from a tag installs where the page would. */
    val packId: String? = null,
    /** Game versions folded into chips, see [gameVersionChips]. */
    val gameVersions: List<GameVersionGroup> = emptyList(),
    val loaders: List<String> = emptyList(),
    /** The project's own tags, primary and additional together. */
    val categories: List<String> = emptyList(),
    val clientSide: String? = null,
    val serverSide: String? = null,
    val licenseId: String? = null,
    val licenseName: String? = null,
    val published: String? = null,
    val updated: String? = null,
    val links: List<ProjectLink> = emptyList(),
    val disclosures: List<Disclosure> = emptyList(),
    /** Who the catalogue credits, apart from [authors], which the archive itself declares. */
    val creators: List<ProjectCreator> = emptyList(),
    val authors: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val sizeBytes: Long? = null,
    /** The runtime a pack starts on, as its source labels it. */
    val runtime: String? = null,
    /** The sign-in providers a pack asks for, by provider key. */
    val signIn: List<String> = emptyList(),
    /** How many mods a pack's current build lists. */
    val modsCount: Int? = null,
    /**
     * Whether the source has a licence and the dates to give at all. The catalogue
     * always does, so its silence is an answer. The mirror has neither.
     */
    val answersLicence: Boolean = true,
    val answersPublished: Boolean = true,
    val answersUpdated: Boolean = true,
    /** The published packs whose current build ships the project, by name, where the source says. */
    val usedBy: List<String> = emptyList(),
)

/** A project as the catalogue describes it. */
data class ProjectDetails(
    val id: String,
    val slug: String,
    val title: String,
    val projectType: String = ProjectTypes.MOD,
    val description: String = "",
    /** Markdown. */
    val body: String = "",
    val categories: List<String> = emptyList(),
    val additionalCategories: List<String> = emptyList(),
    val iconUrl: String? = null,
    val gallery: List<GalleryItem> = emptyList(),
    val downloads: Long = 0,
    val followers: Long = 0,
    val loaders: List<String> = emptyList(),
    val gameVersions: List<String> = emptyList(),
    val clientSide: String = "unknown",
    val serverSide: String = "unknown",
)

/** What the mirror's registry says about a file the catalogue could not name. */
data class MirrorProject(
    val name: String,
    val iconUrl: String? = null,
    /** Where the file is published when the mirror knows of a host beside itself, CurseForge say. */
    val publishedOn: String? = null,
    /** Its releases as the versions table reads builds. */
    val builds: List<ProjectBuild> = emptyList(),
)

/** What a file on disk declares about itself. */
data class InstalledFile(
    val displayName: String,
    val version: String? = null,
    val description: String? = null,
    val homepageUrl: String? = null,
)

/** One file of a build. [primary] is the one an install takes. */
data class BuildFile(val filename: String, val size: Long, val primary: Boolean)

/**
 * Where a build is fetched as one archive, the sha1 the source publishes for it and its
 * size, -1 where the source does not say. The archive carries the index that pins every
 * other file, so it is the one download nothing else vouches for.
 */
data class BuildDownload(val url: String, val sha1: String? = null, val size: Long = -1L)

/**
 * One build of a project or of a pack, as a version table, a changelog and a build's
 * own page read it. A field a source does not publish stays null.
 */
data class ProjectBuild(
    val id: String,
    val name: String,
    val versionNumber: String,
    /** `release`, `beta` or `alpha`. */
    val versionType: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    /** RFC 3339, or blank where the source does not date the build. */
    val datePublished: String,
    val downloads: Long? = null,
    val changelog: String? = null,
    /** What the build ships. Empty where the source does not list files. */
    val files: List<BuildFile> = emptyList(),
    val modsCount: Int? = null,
    val sizeBytes: Long? = null,
    /** The one archive an install fetches, where the source serves a build as one. */
    val download: BuildDownload? = null,
) {
    /** The name a row shows: the number where there is one. */
    val label: String get() = versionNumber.ifBlank { name }
}

/** The things an author can say about another project, in reading order. */
enum class DependencyKind { Required, Optional, Incompatible, Embedded }

/**
 * How one build depends on another project, with the project named. [versionNumber]
 * is present only where the author pinned an exact build. [title] is blank for a
 * dependency that names neither a project nor a file.
 */
data class VersionDependency(
    val projectId: String?,
    val title: String,
    val iconUrl: String?,
    val versionNumber: String?,
    val kind: DependencyKind,
)

/**
 * What a project page can do about putting the project into a pack. "Cannot" and
 * "already there" are different answers, and the pack's name rides along so a button
 * can say where it is going.
 */
sealed interface InstallOffer {
    /** No pack behind the page, and nothing to install into. */
    data object None : InstallOffer

    /** No pack behind the page, so the install asks which one, see [CatalogueKeys.ChooseInstall]. */
    data object Choose : InstallOffer

    data class Install(val packName: String) : InstallOffer

    data class Present(val packName: String) : InstallOffer
}

/** One project an install left out: its name, and the reason. */
data class LeftOut(val title: String, val reason: SkipReason)

/**
 * Everything one project page has found out, and what its install came to.
 *
 * A page belongs to one visit, [visit], which the page names as it is shown: a page
 * kept for a later visit reads as the start of a new one until that visit is shown.
 */
data class ProjectPageView(
    val target: ProjectTarget,
    val visit: String,
    val loading: Boolean = true,
    /** The lookup itself did not run, never the same as a catalogue that has no entry. */
    val failed: Boolean = false,
    val project: ProjectDetails? = null,
    val mirror: MirrorProject? = null,
    /** The archive on disk, for a page opened on an installed file. */
    val file: InstalledFile? = null,
    val install: InstallOffer = InstallOffer.None,
    val installing: Boolean = false,
    /** The last install did not land. */
    val installFailed: Boolean = false,
    /** The project has nothing that runs on the pack, which a retry will not change. */
    val installNoBuild: Boolean = false,
    /** Required dependencies the last install had no build for. */
    val installMissing: List<String> = emptyList(),
    val installLeftOut: List<LeftOut> = emptyList(),
    /** Why the last install did not run at all, when it did not. */
    val installRefusal: InstallRefusal? = null,
    /** Whether an install could ever be offered here, known before anything loads. */
    val installPossible: Boolean = false,
    /** What the pack behind the page runs, blank where it is not known. */
    val packMcVersion: String = "",
    val packLoader: String = "",
    /** Every loader a build may be published for and still have a place in the pack. */
    val packLoaders: List<String> = emptyList(),
    /** Every build the catalogue has, or null until something asks with [CatalogueKeys.LoadVersions]. */
    val versions: List<ProjectBuild>? = null,
    val versionsFailed: Boolean = false,
    val versionsLoading: Boolean = false,
    /** The catalogue's version list, for folding a build's versions, empty until something asks. */
    val gameVersionTags: List<GameVersionTag> = emptyList(),
) {
    val source: ProjectSource
        get() = when {
            project != null -> ProjectSource.Catalogue
            mirror != null -> ProjectSource.Mirror
            else -> ProjectSource.Local
        }

    /** The title the header shows, from whichever side can answer. */
    val title: String
        get() = project?.title
            ?: mirror?.name
            ?: file?.displayName
            ?: when (target) {
                is ProjectTarget.Catalogue -> target.projectId
                is ProjectTarget.Installed -> target.fileName
            }

    val description: String? get() = project?.description?.takeIf { it.isNotBlank() } ?: file?.description

    val body: String? get() = project?.body?.takeIf { it.isNotBlank() }

    val packId: String? get() = target.packId

    /** Whether the catalogue has an entry at all, once the page has finished looking. */
    val knownToCatalogue: Boolean get() = project != null
}

/** One build of the project a page is on, resolved for its own page. */
data class BuildPageView(
    val versionId: String,
    val visit: String,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val build: ProjectBuild? = null,
    /** Named rather than listed by id, and grouped by what the author was saying. */
    val dependencies: List<VersionDependency> = emptyList(),
)

/** A pack's page as its source describes it. */
data class PackDetails(
    val origin: String,
    val id: String,
    val title: String,
    val tagline: String,
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    val gallery: List<GalleryItem> = emptyList(),
    /** Markdown, null where the source has none. */
    val bodyMarkdown: String? = null,
    /** Every build that can be installed, newest first where the source orders them. */
    val versions: List<ProjectBuild> = emptyList(),
    val tags: List<String> = emptyList(),
    val runtimeLabel: String? = null,
    val slug: String? = null,
    val pageUrl: String? = null,
    val projectType: String? = null,
    val downloads: Long? = null,
    val followers: Long? = null,
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val clientSide: String? = null,
    val serverSide: String? = null,
    val licenseId: String? = null,
    val licenseName: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    val links: List<ProjectLink> = emptyList(),
    val creators: List<ProjectCreator> = emptyList(),
    /** The build an install with no choice made reaches for, null to leave it to [versions]. */
    val latestVersionId: String? = null,
    /** The sign-in providers the pack asks for, by provider key. */
    val signIn: List<String> = emptyList(),
) {
    /** The build an install with no choice made takes: the source's pointer, else the newest. */
    val latest: ProjectBuild? get() = versions.firstOrNull { it.id == latestVersionId } ?: versions.firstOrNull()
}

/** Where a pack page stands. */
sealed interface PackPageStatus {
    data object Loading : PackPageStatus

    data class Loaded(val details: PackDetails) : PackPageStatus

    /** Not read. [message] is the source's own words, null where there were none. */
    data class Failed(val message: String? = null) : PackPageStatus
}

/** One pack page, for the visit [visit] names, see [ProjectPageView]. */
data class PackPageView(
    val origin: String,
    val packId: String,
    val visit: String,
    val status: PackPageStatus = PackPageStatus.Loading,
) {
    val details: PackDetails? get() = (status as? PackPageStatus.Loaded)?.details
}

/** A project page shown for the visit [visit], on the page level it is drawn in. */
data class ProjectVisit(val target: ProjectTarget, val visit: String)

/** A build's page shown for the visit [visit], over the project page of the same level. */
data class BuildVisit(val versionId: String, val visit: String)

/** A pack page shown for the visit [visit]. */
data class PackVisit(val origin: String, val packId: String, val visit: String)

/** What a catalogue pack's page is known by to the rail and in [CatalogueKeys.Names], as a project page is by its target's key. */
fun packTargetKey(origin: String, packId: String): String = "pack:$origin:$packId"

/** An install from a project page: [versionId] by name, or null for the newest build that runs on the pack. */
data class ProjectInstall(val versionId: String? = null)
