package hivens.ui.screens.mod

import hivens.core.api.dto.modrinth.ModrinthDisclosure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a page's facts came from.
 *
 * [Local] is not a degraded [Catalogue]. A jar on disk answers some questions
 * better than the catalogue does, because it IS the thing, and cannot answer
 * others at all. The page keeps its shape either way and marks what it does not
 * know, rather than switching to a different, smaller page.
 */
enum class ProjectSource { Catalogue, Local }

/** One of the author's own addresses, named by what it is rather than by its host. */
enum class ProjectLinkKind { Issues, Source, Wiki, Discord, Donate }

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
     * Game versions as chips, already folded into ranges.
     *
     * Folded by the page and not by the rail, because folding needs the
     * catalogue's canonical release order and the rail has no business fetching
     * anything: it renders what the open page knows. A file found on disk
     * contributes the one version it was built against and a question mark for
     * the range it might also run on, which is not knowable from one build.
     */
    val gameVersionLabels: List<String> = emptyList(),
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
 */
class OpenProjectState {
    private val _open = MutableStateFlow<OpenProject?>(null)
    val open: StateFlow<OpenProject?> = _open.asStateFlow()

    fun publish(project: OpenProject?) {
        _open.value = project
    }

    /**
     * Takes the rail down only if it is still describing [targetKey].
     *
     * A page clears on the way out, and the shell animates between pages, so the
     * one LEAVING is disposed after the one arriving has already published. An
     * unconditional clear then wiped the rail a frame after the new page filled
     * it: an empty panel beside a loaded project, and a breadcrumb that fell back
     * to the raw catalogue id because the title it reads had just been erased.
     */
    fun clearIf(targetKey: String) {
        if (_open.value?.targetKey == targetKey) _open.value = null
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

    data class Install(val packName: String) : InstallAction

    data class Present(val packName: String) : InstallAction
}
