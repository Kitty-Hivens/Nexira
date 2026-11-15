package hivens.catalogue

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.PackOrigins
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ResultsScroll
import hivens.catalogue.api.ScrollMark
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SearchStatus
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.ModInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One window's search as Browse drives it: what showing the results runs and leaving
 * them stops, what typing asks for, how pages of a pack list follow the list they
 * belong to, and which search an install from a card goes to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchServiceTest {

    private val dir: Path = Files.createTempDirectory("catalogue-search-test")

    /** The window's scopes, stopped after each test. */
    private val windows = ArrayList<CoroutineScope>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        windows.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    /**
     * A window's scope on the test's clock. Not the test's background scope: what runs
     * there is left out when the test advances its clock.
     */
    private fun TestScope.window(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { windows += it }

    private fun card(id: String) = CataloguePack(PackOrigin.Mirror, id, id, "")

    private fun pack(id: String) = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Local, id),
        displayName = id,
        instanceDirName = id,
        createdAtEpoch = 0L,
        cachedManifest = CachedManifestSnapshot(minecraftVersion = "1.21.1", loaderName = "fabric", loaderVersion = "0.16.10", javaMajor = 21),
    ).also { Files.createDirectories(dir.resolve(id).resolve("mods")) }

    /** A pack source whose first page is [stream] and whose later pages are [page]. */
    private class Source(
        private val stream: suspend (call: Int) -> Flow<List<CataloguePack>>,
        private val page: suspend (call: Int, page: Int) -> List<CataloguePack> = { _, _ -> emptyList() },
    ) : IPackCatalogueService {
        override val origin: PackOrigin = PackOrigin.Mirror
        val asked = mutableListOf<String>()
        val pages = mutableListOf<Int>()

        override suspend fun search(query: String, page: Int): List<CataloguePack> {
            pages += page
            return page(pages.size, page)
        }

        override fun searchStream(query: String, page: Int): Flow<List<CataloguePack>> = flow {
            asked += query
            stream(asked.size).collect { emit(it) }
        }

        override suspend fun details(packId: String): CataloguePackDetails = error("no pack page in a search test")
        override suspend fun versions(packId: String): List<CataloguePackVersion> = emptyList()
    }

    /** The catalogue's projects and the installer, as a project search asks them. */
    private class Projects : ProjectCatalogue {
        var held: Set<String> = emptySet()
        val searched = mutableListOf<String>()
        val installs = mutableListOf<Pair<String, String>>()

        override suspend fun search(type: String, query: String, filters: Set<SearchFilter>, offset: Int, limit: Int, sort: BrowseSort): List<ProjectHit> {
            searched += query
            return if (offset == 0) listOf(ProjectHit("sodium"), ProjectHit("iris")) else emptyList()
        }

        override suspend fun installNewest(projectId: String, pack: ContentDestination.Pack): ModInstaller.Outcome {
            installs += projectId to pack.instanceId
            return ModInstaller.Outcome(installed = listOf("$projectId.jar"))
        }

        override suspend fun presentProjects(dir: Path): Set<String> = held
    }

    private fun TestScope.browse(vararg packs: PackInstance): BrowseState {
        val byId = packs.associateBy { it.id }
        return BrowseState(listOf(PackOrigins.MIRROR), { byId[it] }, dir, window(), io = StandardTestDispatcher(testScheduler))
    }

    private fun TestScope.service(
        browse: BrowseState,
        packs: IPackCatalogueService? = null,
        projects: ProjectCatalogue = Projects(),
        packSnapshots: PackSnapshots = PackSnapshots(),
    ): SearchService {
        val steps = StandardTestDispatcher(testScheduler)
        val window = window()
        return SearchService(
            browse = browse,
            catalogueFor = { if (it == PackOrigins.MIRROR) packs else null },
            projects = projects,
            packSnapshots = packSnapshots,
            projectSnapshots = ProjectSnapshots { 0L },
            scope = window,
            work = window,
            serial = steps,
            io = steps,
        )
    }

    // ── Showing ──────────────────────────────────────────────────────

    /** Installed from the project page while Browse was away: the card read Install on the way back. */
    @Test
    fun `showing again reads again what the pack holds, and asks the catalogue nothing it kept`() = runTest {
        val browse = browse(pack("p"))
        browse.setKind(ProjectKind.Mod)
        browse.setTarget("p")
        val projects = Projects()
        val search = service(browse, projects = projects)
        search.show("browse")
        advanceUntilIdle()
        assertEquals("p", search.results.value.question?.packId)
        assertEquals(emptySet(), search.results.value.present)
        val asked = projects.searched.size
        search.hide("browse")
        advanceUntilIdle()

        Files.writeString(dir.resolve("p").resolve("mods").resolve("sodium.jar"), "a build")
        projects.held = setOf("sodium")
        search.show("browse again")
        advanceUntilIdle()

        assertEquals(setOf("sodium"), search.results.value.present)
        assertEquals(listOf("sodium", "iris"), search.results.value.projects.map { it.projectId })
        assertEquals(asked, projects.searched.size, "the kept answer is shown, not asked for again")
    }

    @Test
    fun `showing again after leaving shows the kept list at once and asks the source behind it`() = runTest {
        val fresh = CompletableDeferred<List<CataloguePack>>()
        val source = Source(stream = { call -> flow { emit(if (call == 1) listOf(card("a")) else fresh.await()) } })
        val search = service(browse(), packs = source)
        search.show("browse")
        advanceUntilIdle()
        assertEquals(listOf("a"), search.results.value.packs.map { it.id })
        search.hide("browse")
        advanceUntilIdle()

        search.show("browse again")
        runCurrent()
        assertEquals(2, source.asked.size, "the source is asked again")
        assertEquals(SearchStatus.Loaded, search.results.value.status, "with the kept list up meanwhile")
        assertEquals(listOf("a"), search.results.value.packs.map { it.id })

        fresh.complete(listOf(card("a"), card("b")))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), search.results.value.packs.map { it.id })
    }

    @Test
    fun `a search that failed is asked again on the next showing`() = runTest {
        val source = Source(stream = { call -> flow { if (call == 1) throw IOException("offline") else emit(listOf(card("a"))) } })
        val search = service(browse(), packs = source)
        search.show("browse")
        advanceUntilIdle()
        assertTrue(search.results.value.status is SearchStatus.Failed)
        search.hide("browse")

        search.show("browse again")
        advanceUntilIdle()

        assertEquals(SearchStatus.Loaded, search.results.value.status)
        assertEquals(listOf("a"), search.results.value.packs.map { it.id })
    }

    /** Nothing runs before anybody shows the results, so the first showing is the one search. */
    @Test
    fun `the first showing searches once, and a second viewer adds nothing`() = runTest {
        val source = Source(stream = { flow { emit(listOf(card("a"))) } })
        val search = service(browse(), packs = source)
        advanceUntilIdle()
        assertEquals(0, source.asked.size, "nobody shows the results yet")

        search.show("browse")
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
        search.show("another")
        advanceUntilIdle()
        assertEquals(1, source.asked.size)
    }

    /** A source that polls for as long as it is collected, the way the mirror's listing does. */
    private fun polling(polls: () -> Unit) = Source(
        stream = {
            flow {
                while (true) {
                    polls()
                    emit(listOf(card("a")))
                    delay(60_000)
                }
            }
        },
    )

    @Test
    fun `a stream that goes on is not collected once nobody shows the results`() = runTest {
        var polls = 0
        val search = service(browse(), packs = polling { polls++ })
        search.show("browse")
        advanceTimeBy(130_000)
        assertEquals(3, polls, "polled while shown")

        search.hide("browse")
        advanceTimeBy(600_000)
        assertEquals(3, polls, "the person is on another screen")
        search.more()
        search.retry()
        advanceTimeBy(600_000)
        assertEquals(3, polls, "nor asked anything by a page or a retry")
        assertEquals(listOf("a"), search.results.value.packs.map { it.id }, "the last results are kept")
    }

    @Test
    fun `one of two viewers leaving keeps the search running, the last one stops it`() = runTest {
        var polls = 0
        val search = service(browse(), packs = polling { polls++ })
        search.show("first")
        search.show("second")
        advanceTimeBy(10_000)
        assertEquals(1, polls)

        search.hide("first")
        advanceTimeBy(120_000)
        assertEquals(3, polls, "the second still shows the results")

        search.hide("second")
        advanceTimeBy(600_000)
        assertEquals(3, polls)
    }

    /** A list drawn on the way back starts from what the results say, before anything runs again. */
    @Test
    fun `where the list was left comes back with it, and stays through a showing again`() = runTest {
        val source = Source(stream = { flow { emit(listOf(card("a"), card("b"))) } })
        val search = service(browse(), packs = source)
        search.show("browse")
        advanceUntilIdle()
        val question = checkNotNull(search.results.value.question)
        val toTop = search.results.value.toTop

        search.scroll(ResultsScroll(question, ScrollMark(5, 12)))
        search.hide("browse")
        advanceUntilIdle()
        assertEquals(ScrollMark(5, 12), search.results.value.scroll)

        search.show("browse again")
        advanceUntilIdle()
        assertEquals(ScrollMark(5, 12), search.results.value.scroll)
        assertEquals(toTop, search.results.value.toTop, "a list that came back the same is not sent to the top")
    }

    // ── Typing ───────────────────────────────────────────────────────

    @Test
    fun `words typed and taken back before the wait is over are not searched`() = runTest {
        val source = Source(stream = { flow { emit(listOf(card("a"))) } })
        val browse = browse()
        val search = service(browse, packs = source)
        search.show("browse")
        browse.setQuery("ab")
        advanceUntilIdle()
        assertEquals(listOf("", "ab"), source.asked)

        browse.setQuery("abc")
        runCurrent()
        advanceTimeBy(100)
        browse.setQuery("ab")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("", "ab"), source.asked, "the box shows the words searched")
        assertEquals("ab", search.results.value.question?.query)
    }

    // ── Pages ────────────────────────────────────────────────────────

    @Test
    fun `a fresh first page drops a later page asked for the list it replaced`() = runTest {
        val refreshed = CompletableDeferred<Unit>()
        val secondPage = CompletableDeferred<List<CataloguePack>>()
        val source = Source(
            stream = {
                flow {
                    emit(listOf(card("a"), card("b")))
                    refreshed.await()
                    emit(listOf(card("x"), card("y")))
                }
            },
            page = { call, _ -> if (call == 1) secondPage.await() else listOf(card("z")) },
        )
        val snapshots = PackSnapshots()
        val search = service(browse(), packs = source, packSnapshots = snapshots)
        search.show("browse")
        advanceUntilIdle()
        search.more()
        runCurrent()
        assertEquals(listOf(1), source.pages, "the next page is on its way")

        refreshed.complete(Unit)
        advanceUntilIdle()
        secondPage.complete(listOf(card("c")))
        advanceUntilIdle()

        assertEquals(listOf("x", "y"), search.results.value.packs.map { it.id })
        assertEquals(listOf("x", "y"), snapshots.get(PackOrigins.MIRROR, "")?.packs?.map { it.id }, "nor kept for a return")
        assertFalse(search.results.value.endReached)

        search.more()
        advanceUntilIdle()
        assertEquals(listOf(1, 1), source.pages, "the fresh list pages from its own start")
        assertEquals(listOf("x", "y", "z"), search.results.value.packs.map { it.id })
    }

    // ── Installs ─────────────────────────────────────────────────────

    /** Runs each step only when told, so a click can land between any two of them. */
    private class Steps : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun step(): Boolean {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return false
            next.run()
            return true
        }

        fun drain() {
            var ran = 0
            while (step()) check(++ran < 10_000) { "the steps never settle" }
        }
    }

    /**
     * The card is clicked while the list still shows the search into "a", and the
     * search into "b" is already queued ahead of the click. Every point the click can
     * land at is tried, up to the one where the list has moved on.
     */
    @Test
    fun `an install goes where the list showed at the click, however far the next search has got`() {
        var clickedAt = 0
        while (true) {
            val steps = Steps()
            val work = CoroutineScope(SupervisorJob())
            val packs = mapOf("a" to pack("a"), "b" to pack("b"))
            val browse = BrowseState(listOf(PackOrigins.MIRROR), { id -> packs[id] }, dir, CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined)
            browse.setKind(ProjectKind.Mod)
            browse.setTarget("a")
            val projects = Projects()
            val search = SearchService(
                browse = browse,
                catalogueFor = { null },
                projects = projects,
                packSnapshots = PackSnapshots(),
                projectSnapshots = ProjectSnapshots { 0L },
                scope = work,
                work = work,
                serial = steps,
                io = steps,
            )
            search.show("browse")
            steps.drain()
            assertEquals("a", search.results.value.question?.packId)

            browse.setTarget("b")
            repeat(clickedAt) { steps.step() }
            if (search.results.value.question?.packId != "a") {
                work.cancel()
                break
            }
            search.install("sodium")
            steps.drain()

            assertEquals(listOf("sodium" to "a"), projects.installs.toList(), "clicked after $clickedAt step(s)")
            work.cancel()
            clickedAt++
        }
        assertTrue(clickedAt > 1, "the next search took more than one step to show")
    }
}
