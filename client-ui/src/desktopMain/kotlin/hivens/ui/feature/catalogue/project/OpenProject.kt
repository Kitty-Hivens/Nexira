package hivens.ui.feature.catalogue.project

import hivens.core.api.dto.modrinth.ModrinthDisclosure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a page's facts came from.
 *
 * [Mirror] is the mirror's registry answering for a file the catalogue does not
 * carry: a name, authors, releases and relations, and no counts, dates or licence.
 *
 * [Local] is not a degraded [Catalogue]. A jar on disk answers some questions
 * better than the catalogue does, because it IS the thing, and cannot answer
 * others at all. The page keeps its shape either way and marks what it does not
 * know, rather than switching to a different, smaller page.
 */
enum class ProjectSource { Catalogue, Mirror, Local }

/** One of the author's own addresses, named by what it is rather than by its host. */
enum class ProjectLinkKind { Issues, Source, Wiki, Discord, Donate, Page }

/**
 * [label] overrides the kind's own name where the destination has one of its own.
 *
 * Donations are the case: "support the author" five times over is a list that
 * says nothing, while Patreon, Ko-fi and PayPal are the choice the reader is
 * actually making.
 */
data class ProjectLink(val kind: ProjectLinkKind, val url: String, val label: String? = null)

/** One person credited on a project, and what the project calls their part in it. */
data class ProjectCreator(
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
    val owner: Boolean = false,
)

/**
 * What the right rail knows about the project the reader is looking at.
 *
 * Deliberately not a `ModrinthProject`. The rail must render for a jar the
 * catalogue has never indexed, and it must not care which of the two screens
 * opened the page, so the page reduces whatever it has to this and publishes it.
 * A field it cannot fill stays null, which the rail draws as a question mark
 * rather than as an answer.
 */
data class OpenProject(
    /**
     * Which [ModTarget] this describes, as its key.
     *
     * Carried so a reader of this state can tell whether it is about the thing
     * they are asking about. The breadcrumb needs exactly that: a trail entry is
     * not always the screen on top, and a label taken from whatever page happens
     * to be open would name the wrong project the moment it is not.
     */
    val targetKey: String,
    val title: String,
    val slug: String,
    val source: ProjectSource,
    /**
     * The page has not heard back yet. The rail draws its blocks with nothing in
     * them rather than the question marks and "not stated" that mean the answer
     * came back empty: an answer still on its way is not an answer.
     */
    val pending: Boolean = false,
    /**
     * The catalogue's project type, `mod` or `shader` and the rest, so a tag can
     * open a search of the same kind. Null for a file the catalogue does not know.
     */
    val projectType: String? = null,
    /**
     * The pack behind the page, the one the reader came from or the one the file
     * sits in, so a search opened from a tag installs where the page would.
     */
    val packId: String? = null,
    /**
     * Game versions as chips, already folded into ranges, each with the versions
     * it stands for.
     *
     * Folded by the page and not by the rail, because folding needs the
     * catalogue's canonical release order and the rail has no business fetching
     * anything: it renders what the open page knows. A file found on disk
     * contributes the one version it was built against and a question mark for
     * the range it might also run on, which is not knowable from one build.
     */
    val gameVersions: List<GameVersionGroup> = emptyList(),
    val loaders: List<String> = emptyList(),
    /**
     * The project's own tags, primary and additional together.
     *
     * A block of its own in the rail rather than three words squeezed under the
     * title: the catalogue puts them there because a reader scanning a project
     * reads what it IS separately from what it runs on, and the header has a
     * tagline to carry already.
     */
    val categories: List<String> = emptyList(),
    val clientSide: String? = null,
    val serverSide: String? = null,
    val licenseId: String? = null,
    val licenseName: String? = null,
    /** How long ago, as the rail reads it. */
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    /** The exact moment, for the tooltip behind each of the two above. */
    val publishedExact: String? = null,
    val updatedExact: String? = null,
    val links: List<ProjectLink> = emptyList(),
    val disclosures: List<ModrinthDisclosure> = emptyList(),
    /**
     * Who is credited, from the catalogue's team.
     *
     * Separate from [authors], which is what the archive itself declares. A jar
     * names a string; the catalogue names people with avatars and roles, and
     * flattening the second into the first would throw both away.
     */
    val creators: List<ProjectCreator> = emptyList(),
    // What the archive itself declares, which the read-only details dialog this
    // page replaces used to be the only place to see. A catalogue entry does not
    // carry any of the three, so they are a file's contribution and not a
    // catalogue one.
    val authors: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val sizeBytes: Long? = null,
    // What a pack says about itself that a mod has no answer for: the runtime it
    // starts on, the sign-in it asks for, and how many mods its current build lists.
    val runtime: String? = null,
    /** The providers it asks the player to be signed in with, by their own names. */
    val signIn: List<String> = emptyList(),
    val modsCount: Int? = null,
    /**
     * Whether the source has a licence and a first publication date to give at all.
     * The catalogue always does, so its silence on either is an answer worth
     * saying. The mirror has neither field, and "no licence stated" over one of its
     * packs would be a claim about the pack made out of a gap in the protocol.
     */
    val answersLicence: Boolean = true,
    val answersPublished: Boolean = true,
    val answersUpdated: Boolean = true,
    /** The published packs whose current build ships the project, by name, where the source says. */
    val usedBy: List<String> = emptyList(),
)

/**
 * The one place that says which project is open, and the only channel between the
 * page and the rail.
 *
 * The page publishes, the rail's widgets read. Neither reaches for the other: the
 * rail is a shell surface that outlives any screen, and a page allowed to write
 * into it directly would be a page that has to know it exists, which is how a
 * screen ends up owning part of the shell it happens to be shown in.
 *
 * Cleared on the way out, because a rail still describing a project after the
 * reader has left it is worse than an empty one.
 *
 * One page owns the rail at a time, the one that [claim]ed it last. The shell
 * animates between pages, so for a moment two are mounted, and the one leaving
 * still has loads in flight. Each answer it published used to land on the rail
 * the arriving page had just filled, and its clear on the way out wiped it. Only
 * the owner's [publish] and [release] are taken now. The owner is the page itself,
 * compared by identity, so two visits to one project are two owners.
 */
class OpenProjectState {
    private val _open = MutableStateFlow<OpenProject?>(null)
    val open: StateFlow<OpenProject?> = _open.asStateFlow()

    private var owner: Any? = null

    /** Makes [page] the one the rail describes. Called as the page comes on screen. */
    fun claim(page: Any) = synchronized(this) {
        owner = page
    }

    /** Shows [project] for [page], when [page] still owns the rail. */
    fun publish(page: Any, project: OpenProject?) = synchronized(this) {
        if (owner === page) _open.value = project
    }

    /** Takes the rail down on [page]'s way out, unless another page has claimed it since. */
    fun release(page: Any) = synchronized(this) {
        if (owner !== page) return@synchronized
        owner = null
        _open.value = null
    }

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * What the pages visited lately were called, by target key.
     *
     * The rail forgets a page the moment the reader leaves it, which is right for
     * the rail and wrong for the trail: a project page left behind under a search
     * opened from one of its tags is still a crumb, and without this it fell back
     * to the raw catalogue id.
     */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Records [title] as what [targetKey] is called, once the page knows it. */
    fun name(targetKey: String, title: String) {
        val current = _names.value
        if (current[targetKey] == title) return
        // The newest last, and the oldest dropped past a bound: a long session
        // reads hundreds of projects and the trail never holds more than a few.
        _names.value = (current - targetKey + (targetKey to title)).entries
            .toList()
            .takeLast(MAX_NAMES)
            .associate { it.key to it.value }
    }

    private companion object {
        const val MAX_NAMES = 64
    }
}

/**
 * What the page can do about putting this project into a pack.
 *
 * Three cases and not a boolean, because "cannot" and "already there" are
 * different answers and a reader deserves to be told which. The pack's name rides
 * along so the button can say where it is going: a launcher can have several open
 * and "Install" alone does not say into what.
 */
sealed interface InstallAction {
    /** No pack behind this page, so nothing to install into. */
    object None : InstallAction

    /** No pack behind this page, so the install asks which one, see [hivens.ui.feature.catalogue.browse.InstallDialog]. */
    object Choose : InstallAction

    data class Install(val packName: String) : InstallAction

    data class Present(val packName: String) : InstallAction
}
