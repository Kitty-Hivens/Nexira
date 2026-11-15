package hivens.catalogue

import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.FilterField
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ResultsScroll
import hivens.catalogue.api.ScrollMark
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SearchQuestion
import hivens.catalogue.api.SearchResults
import hivens.catalogue.api.SearchStatus
import hivens.catalogue.api.BrowseSort
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.ModInstaller
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("hivens.catalogue.Browse")

/**
 * The entries of [page] that are not already in [shown], in the page's own order.
 *
 * A source takes a page number and only some honour it, one that answers with its whole
 * listing every time among them, so a page is accepted by what is new in it, and a page
 * that adds nothing is the end. That reading holds for a source that pages and for
 * one that does not, and keeps a repeat from reaching a keyed list twice.
 */
internal fun newIn(page: List<CatalogueCard>, shown: List<CatalogueCard>): List<CatalogueCard> {
    val seen = shown.mapTo(HashSet(shown.size)) { it.key }
    return page.filterNot { it.key in seen }
}

/** What a project search asks of the project source that searches its type, and of the pack it installs into. */
interface ProjectCatalogue {
    /** The id of the project source that searches [type], or null where none is brought in. */
    fun sourceOf(type: String): String?

    /** One page of results for [type] narrowed by [filters], from [offset] in [sort]'s order. Empty where no source searches [type]. */
    suspend fun search(type: String, query: String, filters: Set<SearchFilter>, offset: Int, limit: Int, sort: BrowseSort): List<ProjectHit>

    /**
     * Installs the newest build of [projectId], found by a search for [type], that runs on
     * [pack] into it, or null when none does. Throws [SourceCannotInstall] where the source
     * that found it cannot install it yet.
     */
    suspend fun installNewest(type: String, projectId: String, pack: ContentDestination.Pack): ModInstaller.Outcome?

    /** The projects the pack in [dir] already carries. */
    suspend fun presentProjects(dir: Path): Set<String>
}

/** The project source [source] found a project the launcher cannot install from it yet. */
class SourceCannotInstall(val source: String?) : Exception("the source '$source' cannot install a project yet")

/**
 * What one window's search found, as [SearchResults], and the installs started from it.
 *
 * It follows the window's [BrowseState]: a change of what is searched starts a new
 * search at once, and a change of the words alone starts one a moment after the last
 * keystroke, so each keystroke does not reach the network. Packs are listed from the
 * source the window picked, stale first and fresh behind it, and paged on request.
 * Projects are searched in the catalogue with the window's filters and the target
 * pack's locks, and a result can be installed into that pack.
 *
 * A search runs only while somebody [show]s the results. Nothing reaches the network
 * or the pack's folders while nobody does: a source that answers with a stream that
 * goes on, one that polls its listing say, is collected only while the results are
 * shown. The words and the filters are still followed meanwhile.
 *
 * Every search is kept for a return to it, see [PackSnapshots] and [ProjectSnapshots],
 * and showing the results again runs it again from what was kept: the kept list goes
 * up at once, scrolled where it was left, and the source and the pack are asked behind
 * it, so an install from a project page or a removal in the Content tab shows on the
 * way back.
 *
 * The work runs one step at a time on [serial], so what a search shows is never written
 * from two places at once. Installs run on [work], the app's, so leaving the window
 * between the build that was clicked and what it requires does not leave a mod without
 * them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchService(
    private val browse: BrowseState,
    /** The pack source with an id, opened, or null where none is brought in. */
    private val catalogueFor: (origin: String) -> OpenPackSource?,
    private val projects: ProjectCatalogue,
    private val packSnapshots: PackSnapshots,
    private val projectSnapshots: ProjectSnapshots,
    scope: CoroutineScope,
    work: CoroutineScope,
    private val debounce: Duration = 350.milliseconds,
    /** Where the search's steps run. It has to run one at a time. */
    serial: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** Where the catalogue and the pack's folders are read. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Moves whenever the modules exchange a source for another instance of it, a jar
     * loaded again say ([SourceShelf.generation]). The search shown then runs again on the
     * source as it is now, and stops reading the one its module let go.
     */
    private val sourceGeneration: StateFlow<Int> = MutableStateFlow(0),
) {
    private val scope = CoroutineScope(scope.coroutineContext + serial)
    private val work = CoroutineScope(work.coroutineContext + serial)

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    /** What a search looks like without its words: a change of any of it is a new search. */
    private data class Shape(
        val kind: ProjectKind?,
        val origin: String,
        val type: String?,
        val sort: BrowseSort,
        val filters: Set<SearchFilter>,
        val destination: ContentDestination.Pack?,
        val hiding: Boolean,
    )

    private val shape = MutableStateFlow(shapeOf(browse.view.value))
    private val submitted = MutableStateFlow(browse.view.value.query)

    /** Counts the retries, each of which runs the same search again. */
    private val asks = MutableStateFlow(0)

    /** Who shows the results now, see [show], and how many times they came to be shown after nobody did. */
    private data class Viewers(val names: Set<String> = emptySet(), val showings: Int = 0)

    private val viewers = MutableStateFlow(Viewers())

    /** One run of the search, or none while nobody shows the results. */
    private data class Ask(val shape: Shape, val query: String, val shown: Boolean)

    // The search shown now, of one of the two kinds. The project search is read by an
    // install off the steps, at the click.
    private var packRun: PackRun? = null
    @Volatile
    private var projectRun: Pair<Shape, ProjectSearch>? = null

    /** Counts the fresh first pages that replaced a pack list, across every listing, see [SearchResults.toTop]. */
    private var toTop = 0

    init {
        this.scope.launch { followBrowse() }
        this.scope.launch {
            // Counted by showing rather than read as shown or not: a leave and a return
            // that land between two reads of the viewers are still a return.
            val showing = viewers.map { if (it.names.isEmpty()) null else it.showings }.distinctUntilChanged()
            // A hidden ask cancels the run under way, the stream it collects included,
            // and leaves its results and its snapshots as they stand.
            combine(shape, submitted, asks, showing, sourceGeneration) { s, q, _, on, _ -> Ask(s, q, on != null) }.collectLatest { ask ->
                if (ask.shown) run(ask.shape, ask.query)
            }
        }
    }

    private fun shapeOf(view: BrowseView): Shape {
        val type = view.type
        if (type == null) return Shape(null, view.origin, null, view.sort, emptySet(), null, false)
        val destination = browse.target?.takeIf { it.view.takes(view.kind) }?.destination
        return Shape(
            kind = view.kind,
            origin = view.origin,
            type = type,
            sort = view.sort,
            filters = browse.searchFiltersFor(type, view.kind),
            destination = destination,
            hiding = view.hideInstalled && destination != null,
        )
    }

    /**
     * Follows the window's browse. A change of the words alone waits [debounce] for the
     * next keystroke. Any other change of what is searched is a new search that takes
     * the words as they are, the way a list opened anew takes them.
     */
    private suspend fun followBrowse() {
        var pending: Job? = null
        var typed = submitted.value
        browse.view.collect { view ->
            val next = shapeOf(view)
            if (next != shape.value) {
                pending?.cancel()
                submitted.value = view.query
                shape.value = next
            } else if (view.query != typed) {
                // Every change of the words starts the wait again, a change back to the
                // words searched now included. Left running, the wait searched for words
                // the box had stopped showing.
                pending?.cancel()
                if (view.query != submitted.value) {
                    pending = scope.launch {
                        delay(debounce)
                        submitted.value = view.query
                    }
                }
            }
            typed = view.query
        }
    }

    private fun question(s: Shape, query: String): SearchQuestion =
        SearchQuestion(s.kind, s.origin, s.type, query, s.sort, s.filters, s.destination?.instanceId, s.hiding)

    private suspend fun run(s: Shape, query: String) {
        if (s.type == null) {
            projectRun = null
            runPacks(s, query)
        } else {
            packRun = null
            if (projects.sourceOf(s.type) == null) {
                // Nobody to ask, which reads apart from a source that answered with nothing.
                log.info("browse: no module brings a source that searches '{}'", s.type)
                projectRun = null
                _results.value = SearchResults(question = question(s, query), status = SearchStatus.NoSource)
                return
            }
            val search = projectRun?.takeIf { it.first == s }?.second ?: newProjectSearch(s).also { projectRun = s to it }
            search.search(query)
        }
    }

    // ── Projects ────────────────────────────────────────────────────────

    private fun newProjectSearch(s: Shape): ProjectSearch {
        val type = checkNotNull(s.type)
        val target = s.destination
        lateinit var made: ProjectSearch
        made = ProjectSearch(
            kind = s.kind,
            destination = target,
            search = { q, offset, limit, excluded ->
                val hidden = excluded.map { SearchFilter(FilterField.Project, it, excluded = true) }
                withContext(io) { projects.search(type, q, s.filters + hidden, offset, limit, s.sort) }
            },
            installInto = { id -> target?.let { t -> withContext(io) { projects.installNewest(type, id, t) } } },
            presentProjects = { val dir = target?.dir; if (dir == null) emptySet() else withContext(io) { projects.presentProjects(dir) } },
            hideInstalled = s.hiding,
            session = projectSnapshots,
            keyOf = { q -> ProjectSnapshots.Key(type, q, s.sort, s.filters, target?.instanceId, s.hiding) },
            contentStamp = { target?.dir?.let { dir -> withContext(io) { contentStampOf(dir) } } },
            changed = { if (projectRun?.second === made) publishProjects(s, made) },
        )
        return made
    }

    private fun publishProjects(s: Shape, search: ProjectSearch) {
        val results = search.results
        _results.value = SearchResults(
            question = question(s, search.shownQuery ?: search.asked ?: submitted.value),
            projectSource = s.type?.let(projects::sourceOf),
            status = when {
                results == null -> SearchStatus.Loading
                search.searchFailed -> SearchStatus.Failed()
                results.isEmpty() -> SearchStatus.Empty
                else -> SearchStatus.Loaded
            },
            projects = results.orEmpty(),
            endReached = search.endReached,
            scroll = search.restoredScroll,
            present = search.present,
            working = search.working,
            problems = search.problems,
            missing = search.missing,
        )
    }

    // ── Packs ───────────────────────────────────────────────────────────

    /** One pack listing: its source and words, and how far it was paged. */
    private inner class PackRun(val shape: Shape, val query: String) {
        val question: SearchQuestion = question(shape, query)
        var packs: List<CatalogueCard>? = null
        var status: SearchStatus = SearchStatus.Loading
        var page = 0
        var endReached = false
        var scroll = ScrollMark()
        var catalogue: OpenPackSource? = null

        /** Counts the first pages that replaced the list. A later page asked for before one of them follows the list it replaced. */
        var firstPages = 0

        /** The [firstPages] count a later page is on its way for, null while none is. */
        var pagingFor: Int? = null

        fun publish() {
            if (packRun !== this) return
            _results.value = SearchResults(
                question = question,
                status = status,
                packs = packs.orEmpty(),
                endReached = endReached,
                scroll = scroll,
                toTop = this@SearchService.toTop,
            )
        }

        fun keep() {
            val shown = packs ?: return
            packSnapshots.put(shape.origin, query, PackSnapshots.Snapshot(shown, page, endReached, scroll))
        }
    }

    /**
     * Lists packs from [s]'s source for [query]. What it was last showing goes up first,
     * before anything is asked of the source: a spinner belongs over an empty screen,
     * not over a list the person was reading a moment ago.
     */
    private suspend fun runPacks(s: Shape, query: String) {
        val origin = s.origin
        val run = PackRun(s, query).also { packRun = it }
        val remembered = packSnapshots.get(origin, query)
        log.info(
            "browse: asking {} for \"{}\", {}",
            origin,
            query,
            remembered?.let { "showing ${it.packs.size} remembered pack(s) meanwhile" } ?: "nothing remembered",
        )
        if (remembered != null) {
            run.packs = remembered.packs
            run.status = SearchStatus.Loaded
            run.page = remembered.nextPage
            run.endReached = remembered.endReached
            run.scroll = remembered.scroll
        }
        run.publish()
        val catalogue = catalogueFor(origin)
        if (catalogue == null) {
            log.warn("browse: no module brings the source '{}'", origin)
            run.status = SearchStatus.NoSource
            run.publish()
            return
        }
        run.catalogue = catalogue
        // A source that answers with its whole listing has no further pages, and asking
        // it for one only returns the same list again.
        if (!catalogue.paged) run.endReached = true
        try {
            // Stale first, fresh behind it. An equal list is not news, so a refresh that
            // found nothing new costs the reader nothing.
            catalogue.listing.searchStream(query, page = 0).flowOn(io).collect { packs ->
                if (packs.isEmpty()) {
                    // Answered, with nothing in it, which a report has to tell from a failure.
                    log.info("browse: {} listed no packs for query \"{}\"", origin, query)
                    run.firstPages++
                    run.packs = null
                    run.status = SearchStatus.Empty
                    packSnapshots.put(origin, query, PackSnapshots.Snapshot(emptyList(), nextPage = 0, endReached = true))
                    run.publish()
                    return@collect
                }
                val shown = run.packs.takeIf { run.status == SearchStatus.Loaded }
                // A source with no pages hands over the entire list each time, so its
                // answer is the list: a pack published or withdrawn replaces it where it
                // stands, and the person is not sent back to the top.
                if (!catalogue.paged) {
                    if (packs == shown) {
                        log.debug("browse: {} re-listed the same {} pack(s)", origin, packs.size)
                        return@collect
                    }
                    log.info("browse: {} listed {} pack(s)", origin, packs.size)
                    run.firstPages++
                    run.packs = packs
                    run.status = SearchStatus.Loaded
                    run.page = 0
                    run.endReached = true
                    run.keep()
                    run.publish()
                    return@collect
                }
                // Membership, not order. A catalogue re-ranks constantly, and a shuffled
                // first page is not new content: the pages scrolled onto its end follow
                // from it and stay.
                if (shown != null && newIn(packs, shown).isEmpty()) {
                    log.info("browse: {} re-listed the same {} pack(s)", origin, packs.size)
                    return@collect
                }
                log.info("browse: {} listed {} pack(s)", origin, packs.size)
                run.firstPages++
                run.packs = packs
                run.status = SearchStatus.Loaded
                run.page = 0
                run.endReached = false
                run.scroll = ScrollMark()
                packSnapshots.put(origin, query, PackSnapshots.Snapshot(packs, nextPage = 0, endReached = false))
                toTop++
                run.publish()
            }
        } catch (e: Throwable) {
            // A source's own code may fail to link as well as throw.
            if (!e.isSourceFault) throw e
            // Logged before it is decided what to show, because the branch that shows
            // nothing is the one that most needs a record.
            log.warn("browse: {} failed for query \"{}\"", origin, query, e)
            // A source that failed while something of its own is shown keeps showing it.
            if (run.status != SearchStatus.Loaded) run.status = SearchStatus.Failed(e.message)
            run.publish()
        }
    }

    private suspend fun morePacks(run: PackRun) {
        val current = run.packs?.takeIf { run.status == SearchStatus.Loaded } ?: return
        val catalogue = run.catalogue ?: return
        val list = run.firstPages
        if (run.pagingFor == list || run.endReached) return
        run.pagingFor = list
        try {
            val next = withContext(io) { catalogue.listing.search(run.query, page = run.page + 1) }
            // A fresh first page that landed meanwhile replaced the list this one follows.
            if (packRun !== run || run.firstPages != list) return
            val fresh = newIn(next, current)
            if (fresh.isEmpty()) {
                run.endReached = true
            } else {
                run.page += 1
                run.packs = current + fresh
                run.keep()
            }
            run.publish()
        } catch (e: Throwable) {
            if (!e.isSourceFault) throw e
            // A failure is not an ending. Marking the listing finished on one refused
            // request takes the rest of the catalogue away until the words are typed
            // again. The next scroll asks again.
        } finally {
            if (run.pagingFor == list) run.pagingFor = null
        }
    }

    // ── Commands ────────────────────────────────────────────────────────

    /** Reads the next page of what is shown, unless it has none or one is on its way. */
    fun more() {
        scope.launch {
            if (viewers.value.names.isEmpty()) return@launch
            packRun?.let { morePacks(it) }
            projectRun?.second?.more()
        }
    }

    /** Asks the search again from its first page. */
    fun retry() {
        asks.update { it + 1 }
    }

    /**
     * [viewer] shows the results from now until it calls [hide]. The first one to show
     * them runs the search, again from what was kept when it ran before. A second one
     * while the first still shows them changes nothing.
     */
    fun show(viewer: String) {
        viewers.update { v ->
            when {
                viewer in v.names -> v
                v.names.isEmpty() -> Viewers(setOf(viewer), v.showings + 1)
                else -> v.copy(names = v.names + viewer)
            }
        }
    }

    /** [viewer] stopped showing the results. Once nobody shows them the search stops. */
    fun hide(viewer: String) {
        viewers.update { it.copy(names = it.names - viewer) }
    }

    /**
     * Remembers where the person left the list that answers [scroll]'s question. The
     * shown search carries it in its results too, since a list drawn on the way back
     * starts from what the results say before anything is run again.
     */
    fun scroll(scroll: ResultsScroll) {
        scope.launch {
            val q = scroll.question
            val type = q.type
            if (type == null) {
                val run = packRun
                if (run != null && run.question == q) {
                    run.scroll = scroll.mark
                    if (run.status == SearchStatus.Loaded) run.keep()
                    run.publish()
                } else {
                    packSnapshots.get(q.origin, q.query)?.let { packSnapshots.put(q.origin, q.query, it.copy(scroll = scroll.mark)) }
                }
            } else {
                val shown = projectRun?.takeIf { (s, _) -> question(s, q.query) == q }?.second
                if (shown != null) {
                    shown.rememberScroll(q.query, scroll.mark)
                } else {
                    projectSnapshots.scroll(ProjectSnapshots.Key(type, q.query, q.sort, q.filters, q.packId, q.hiding), scroll.mark)
                }
            }
        }
    }

    /**
     * Installs the project [projectId] into the pack the shown search installs into.
     * The search is the one shown at the click: by the time a step of the search's own
     * gets to the click, the next search can have taken its place.
     */
    fun install(projectId: String) {
        val search = projectRun?.second ?: return
        if (search.destination == null) return
        work.launch { search.install(projectId) }
    }
}
