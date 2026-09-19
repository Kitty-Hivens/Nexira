package hivens.ui.screens.mod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.api.dto.modrinth.ModrinthDependency
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * How one build depends on another project, with the project NAMED.
 *
 * The wire carries ids. A list reading "P7dR8mSH, AANobbMI" is a list a reader
 * has to go and look up one entry at a time, which is the opposite of what a
 * dependency list is for -- and the names cost one cached lookup each, because
 * the catalogue answers a project by id and the page has already asked for some
 * of them.
 *
 * [versionNumber] is present only where the author pinned an exact build. That
 * is a different statement from "needs this project" and reads as one.
 *
 * [title] is blank for a dependency that names neither a project nor a file. The
 * row still appears -- the author said this build needs SOMETHING -- and the page
 * fills the blank with the same word it uses for every other missing fact.
 */
data class VersionDependency(
    val projectId: String?,
    val title: String,
    /**
     * The project's own mark, from the same lookup that supplied its name.
     *
     * Free: naming a dependency already costs one project fetch, and a row that
     * carries the mod's art is one a reader recognises without reading it.
     */
    val iconUrl: String?,
    val versionNumber: String?,
    val kind: DependencyKind,
)

/** The three things an author can say about another project, in reading order. */
enum class DependencyKind { Required, Optional, Incompatible, Embedded }

/**
 * One build, resolved for its own page.
 *
 * Deliberately NOT part of [ModDetailState]: that one answers for a project and
 * is shared with the rail, and a build is a different subject with a different
 * lifetime -- a reader moves between builds without the project changing under
 * them. The page holds both, and each answers only what it is about.
 */
class ModVersionState(
    private val modrinth: ModrinthClient,
) {
    /**
     * False until somebody asks.
     *
     * It used to start true, which reads fine until the page has no project id to
     * ask WITH: the effect returns early, nothing ever clears the flag, and the
     * page spins for good with no failure to offer a retry for. Loading is a thing
     * that is happening, not a thing that might.
     */
    var loading by mutableStateOf(false)
        internal set

    /** The lookup did not run. Never the same as a build that declares nothing. */
    var failed by mutableStateOf(false)
        internal set

    var version by mutableStateOf<ModrinthVersion?>(null)
        internal set

    /** Named rather than listed by id, and grouped by what the author was saying. */
    var dependencies by mutableStateOf<List<VersionDependency>>(emptyList())
        internal set

    suspend fun load(projectId: String, versionId: String) {
        loading = true
        failed = false
        try {
            val resolved = withContext(Dispatchers.IO) { modrinth.resolveVersion(projectId, versionId) }
            // Published before the names are looked up. Naming a dependency is two
            // more round trips EACH, so a build with a dozen of them held a page
            // whose every other fact had already arrived.
            version = resolved
            loading = false
            dependencies = namesFor(resolved.dependencies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Version page: could not resolve {} of {}", versionId, projectId, e)
            failed = true
        }
        loading = false
    }

    /**
     * Puts a name to each id, and keeps the entry when there is none to put.
     *
     * A lookup that fails leaves the id showing, which is worse than a title and
     * far better than the row disappearing: the author said this build needs
     * something, and dropping the row would quietly unsay it.
     */
    private suspend fun namesFor(raw: List<ModrinthDependency>): List<VersionDependency> =
        withContext(Dispatchers.IO) {
            raw.map { dep ->
                // Cooperative: the loop makes two suspend calls per entry and both
                // swallow what they catch, so without this a cancelled page kept
                // walking the whole list making calls nobody was waiting for.
                currentCoroutineContext().ensureActive()
                val id = dep.projectId
                val pin = dep.versionId
                val project = id?.let { runCatching { modrinth.resolveProject(it) }.getOrNull() }
                val title = project?.title
                    ?: dep.fileName?.substringBeforeLast('.')
                    ?: id
                    .orEmpty()
                // The pinned build, where there is one. A second call per
                // dependency that only ever adds a version number, so a failure
                // here leaves the row saying the shorter, still-true thing.
                val pinned = if (id != null && pin != null) {
                    runCatching { modrinth.resolveVersion(id, pin).versionNumber }.getOrNull()
                } else {
                    null
                }
                VersionDependency(
                    projectId = id,
                    title = title,
                    iconUrl = project?.iconUrl,
                    versionNumber = pinned,
                    kind = kindOf(dep.dependencyType),
                )
            }
        }

    private fun kindOf(type: String): DependencyKind = when (type) {
        "required"     -> DependencyKind.Required
        "incompatible" -> DependencyKind.Incompatible
        "embedded"     -> DependencyKind.Embedded
        else           -> DependencyKind.Optional
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ModVersionState::class.java)

    }
}
