package hivens.catalogue

import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectTypes
import hivens.catalogue.api.ScrollMark
import hivens.launcher.content.ContentKind
import hivens.launcher.content.folderName
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.ModInstaller
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

private val log = LoggerFactory.getLogger("hivens.catalogue.ProjectSearch")

/**
 * What [instanceDir]'s content folders look like, as names, sizes and change times: a
 * cheap local answer to whether anything was added, removed or replaced since the last
 * look, without hashing a file or asking the catalogue. Null when the folders cannot be
 * listed, which never matches a stamp taken before.
 */
internal fun contentStampOf(instanceDir: Path): String? = runCatching {
    ContentKind.entries.joinToString("|") { kind ->
        val dir = instanceDir.resolve(kind.folderName())
        if (!Files.isDirectory(dir)) "" else Files.list(dir).use { files ->
            files.map { "${it.fileName}:${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}" }.sorted().collect(Collectors.toList()).joinToString(",")
        }
    }
}.getOrNull()

/**
 * One search of the catalogue for projects: its pages of results, and the installs
 * started from them.
 *
 * With a [destination] every install goes into that pack, and what the pack already
 * holds is read before the first page, so a search that hides it can. Without one a
 * result only opens its page or the install that asks where.
 *
 * What a search hides is the pack as it was when the search began. A project installed
 * from the list stays on it, reading installed, and the pages after it are asked for
 * with the same exclusion: one more excluded id moves every later result up a place,
 * and the page read next would start one result past where it should.
 *
 * With a [session] a search already shown is shown again as it was left, see
 * [ProjectSnapshots], and [keyOf] names a query's place in it.
 *
 * Not thread safe: its owner calls it from one thread at a time, and [changed] says
 * when what it shows changed.
 */
internal class ProjectSearch(
    /** The kind searched, null for modpacks, which are not installed into a pack. */
    val kind: ProjectKind?,
    val destination: ContentDestination.Pack?,
    /** One page of results for a query, from an offset in the catalogue's own order, with ids left out by the catalogue. */
    private val search: suspend (query: String, offset: Int, limit: Int, excluded: Collection<String>) -> List<ProjectHit>,
    /** Installs a result into the destination pack, or null when no build of it runs there. */
    private val installInto: suspend (projectId: String) -> ModInstaller.Outcome?,
    /** The projects the destination pack already carries. */
    private val presentProjects: suspend () -> Set<String>,
    /** Whether what the pack already carries is left out of the results. */
    private val hideInstalled: Boolean = false,
    private val session: ProjectSnapshots? = null,
    private val keyOf: (query: String) -> ProjectSnapshots.Key? = { null },
    /** What the destination pack's content folders look like now, see [contentStampOf]. */
    private val contentStamp: suspend () -> String? = { null },
    private val changed: () -> Unit = {},
) {
    /** Where the list was scrolled to when this search was last shown, for the list to start at. */
    var restoredScroll: ScrollMark = ScrollMark()
        private set

    /** The question whose answer [results] holds, which is what a scroll position belongs to. */
    var shownQuery: String? = null
        private set

    /** The question asked last. */
    var asked: String? = null
        private set

    private var presentStamp: String? = null

    /** When this search last wrote into the session, so an older one cannot write over a newer one. */
    private var keptAt = Long.MIN_VALUE

    /** Null while the first page is in flight. */
    var results: List<ProjectHit>? = null
        private set

    /** The search could not be run. Not the same as a search that found nothing. */
    var searchFailed: Boolean = false
        private set

    var endReached: Boolean = false
        private set

    private var loadingMore = false
    private var presentRead = false

    /** Which search is current. A page that arrives for an older one is dropped. */
    private var generation = 0

    /** Where the next page starts in the catalogue's order, which is not the shown count once some are hidden here. */
    private var offset = 0

    /** What this search leaves out, fixed when it began. */
    private var hidden: Set<String> = emptySet()

    /** Projects the destination pack already carries, read from its folders. */
    var present: Set<String> = emptySet()
        private set

    var working: Set<String> = emptySet()
        private set

    var problems: Map<String, InstallProblem> = emptyMap()
        private set

    /**
     * How many required dependencies an install that landed went without, by project.
     * The build is in, and the game will refuse it until they are.
     */
    var missing: Map<String, Int> = emptyMap()
        private set

    suspend fun loadPresent() {
        if (destination == null) return
        val stamp = contentStamp()
        // A row reading Install for something already there is a wasted click, and a
        // browser that refuses to open over a folder it cannot read is worse.
        present = present + runCatching { presentProjects() }
            .onFailure { if (it is CancellationException) throw it else log.warn("reading what the pack holds failed", it) }
            .getOrDefault(emptySet())
        // Marked only once the read is over. Marked before it, a search cancelled while
        // it ran, which typing on does to the search before it, left the pack unread for
        // the rest of this search: installed projects offered Install and hiding hid nothing.
        presentStamp = stamp
        presentRead = true
        changed()
    }

    suspend fun search(q: String) {
        val gen = ++generation
        if (restore(q)) {
            changed()
            if (packChanged()) {
                readPresentAgain()
                keep()
            }
            return
        }
        asked = q
        // A new question starts at the top of its answer, not where the last one was left.
        restoredScroll = ScrollMark()
        results = null
        searchFailed = false
        endReached = false
        offset = 0
        changed()
        // Asked again of a search that read the pack before, which a return to Browse
        // does once the kept search has gone stale.
        if (!presentRead) loadPresent() else if (packChanged()) readPresentAgain()
        if (gen != generation) return
        hidden = if (hideInstalled) present else emptySet()
        val first = nextBatch(q, gen, known = emptySet())
        if (gen != generation) return
        if (first == null) {
            searchFailed = true
            results = emptyList()
            changed()
            return
        }
        results = first
        shownQuery = q
        keep()
        changed()
    }

    /**
     * Whether the pack's folders changed since what it holds was read: a mod installed
     * from a project page, one removed in the Content tab. They are read again only
     * when they did.
     */
    private suspend fun packChanged(): Boolean = destination != null && contentStamp() != presentStamp

    private suspend fun readPresentAgain() {
        present = emptySet()
        presentRead = false
        loadPresent()
    }

    /**
     * Shows the search for [q] as it was left, when the session still holds it, and
     * answers whether it did. Nothing is asked of the catalogue or of the pack.
     */
    private fun restore(q: String): Boolean {
        val key = keyOf(q) ?: return false
        val snap = session?.get(key) ?: return false
        asked = q
        searchFailed = false
        endReached = snap.endReached
        offset = snap.offset
        hidden = snap.hidden
        present = present + snap.present
        presentStamp = snap.presentStamp
        presentRead = true
        keptAt = snap.takenAt
        restoredScroll = snap.scroll
        results = snap.results
        shownQuery = q
        return true
    }

    /**
     * Writes where the search has got to into the session, unless a newer search has
     * since. An install started here runs on to its end after the person has left, and
     * its finish used to put this search's shorter list back over the one kept since.
     */
    private fun keep() {
        val q = asked ?: return
        val shown = results ?: return
        if (searchFailed) return
        val key = keyOf(q) ?: return
        val s = session ?: return
        val kept = s.get(key)
        if (kept != null && kept.takenAt > keptAt) return
        val scroll = kept?.scroll ?: restoredScroll
        val now = s.now()
        s.put(
            key,
            ProjectSnapshots.Snapshot(
                results = shown, offset = offset, endReached = endReached, hidden = hidden, present = present,
                presentStamp = presentStamp, scroll = scroll, takenAt = now,
            ),
        )
        keptAt = now
    }

    /**
     * Records where the person has scrolled the answer to [query], for a return to it.
     * Named by the caller, since by the time a list is taken down the search may
     * already be asking the next question. The answer shown now starts there the next
     * time it is drawn.
     */
    fun rememberScroll(query: String, mark: ScrollMark) {
        keyOf(query)?.let { session?.scroll(it, mark) }
        if (query == shownQuery && mark != restoredScroll) {
            restoredScroll = mark
            changed()
        }
    }

    /** The next page, when the list has one and none is already on its way. */
    suspend fun more() {
        val shown = results ?: return
        val q = asked ?: return
        if (endReached || loadingMore) return
        val gen = generation
        loadingMore = true
        try {
            // A page that could not be read is not the end: the next scroll asks again.
            val fresh = nextBatch(q, gen, known = shown.mapTo(HashSet()) { it.projectId }) ?: return
            if (gen == generation && fresh.isNotEmpty()) results = shown + fresh
            if (gen == generation) keep()
            changed()
        } finally {
            loadingMore = false
        }
    }

    /**
     * The next results worth showing, reading on past a page that held nothing new.
     *
     * The catalogue is told to leave out at most [MAX_EXCLUDED] of the hidden ids,
     * since every one of them is a clause in the query, and the rest are dropped here.
     * A pack of a few hundred mods can then fill whole pages with what it already has,
     * so a page that comes back emptied is read past rather than taken as the end.
     * Null when a page could not be read.
     */
    private suspend fun nextBatch(q: String, gen: Int, known: Set<String>): List<ProjectHit>? {
        repeat(MAX_EMPTY_PAGES) {
            val page = fetch(q, offset) ?: return null
            if (gen != generation) return emptyList()
            offset += page.size
            if (page.size < PAGE_SIZE) endReached = true
            val fresh = page.filterNot { it.projectId in hidden || it.projectId in known }
            if (fresh.isNotEmpty() || endReached) return fresh
        }
        endReached = true
        return emptyList()
    }

    private suspend fun fetch(q: String, offset: Int): List<ProjectHit>? = try {
        search(q, offset, PAGE_SIZE, hidden.take(MAX_EXCLUDED))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("searching {} for \"{}\" failed", kind ?: ProjectTypes.MODPACK, q, e)
        null
    }

    /**
     * Installs the project [id] into the destination pack. A row reads installed only
     * when the build landed, and what the install reports the pack holds afterwards is
     * taken whole, so a dependency pulled in behind it stops offering itself too.
     */
    suspend fun install(id: String) {
        working = working + id
        problems = problems - id
        missing = missing - id
        changed()
        val problem = try {
            val outcome = installInto(id)
            if (outcome?.ok == true) present = present + outcome.present + id
            if (outcome?.ok == true && outcome.missing.isNotEmpty()) missing = missing + (id to outcome.missing.size)
            if (outcome == null) InstallProblem.NoBuild else problemOf(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("installing {} failed", id, e)
            InstallProblem.NotLanded
        } finally {
            working = working - id
        }
        if (problem != null) problems = problems + (id to problem)
        keep()
        changed()
    }

    companion object {
        const val PAGE_SIZE = 30

        /** Ids the catalogue is asked to leave out. The query carries each one, so it is bounded. */
        const val MAX_EXCLUDED = 200

        /** Pages read past in a row because everything on them was hidden, before the list ends. */
        const val MAX_EMPTY_PAGES = 4
    }
}
