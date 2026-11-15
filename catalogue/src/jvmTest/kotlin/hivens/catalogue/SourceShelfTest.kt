package hivens.catalogue

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.InstallSubject
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.ProjectSearchContribution
import hivens.catalogue.api.ProjectSearcher
import hivens.catalogue.api.SearchFilter
import hivens.catalogue.api.SourceFilter
import hivens.catalogue.api.SourceView
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.net.TransferEngine
import hivens.launcher.InstallSnapshot
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.InstallTargets
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.ModrinthClient
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * What the shelf takes of the sources the modules bring, and what it refuses: a source
 * whose own code throws or cannot link, one with an id the launcher keeps, one with the
 * id of a source compiled in from another module, and the second of two with one id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceShelfTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = scope.cancel()

    private object NoServices : LevelServices {
        override fun <T : Any> find(type: KClass<T>): T? = null
        override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
    }

    private object NoListing : PackListing {
        override suspend fun search(query: String, page: Int): List<CatalogueCard> = emptyList()
        override suspend fun details(packId: String): PackDetails = error("not read here")
        override suspend fun versions(packId: String): List<ProjectBuild> = emptyList()
    }

    private open class Source(override val id: String, private val opened: AtomicInteger = AtomicInteger()) : PackSourceContribution {
        override val label: String = id
        override fun open(services: LevelServices): PackListing {
            opened.incrementAndGet()
            return NoListing
        }
    }

    /** A source whose declaration fails the way a jar built against another launcher does, with [failure]. */
    private class Broken(id: String, private val failure: Throwable) : Source(id) {
        override val filters: List<SourceFilter> get() = throw failure
    }

    /** A source that declares itself and fails as it is opened. */
    private class FailsToOpen(id: String, private val failure: Throwable) : Source(id) {
        override fun open(services: LevelServices): PackListing = throw failure
    }

    private fun taken(vararg items: Pair<String, ModuleContribution>) =
        MutableStateFlow<List<TakenContribution<ModuleContribution>>>(items.map { (module, item) -> TakenContribution(module, item) })

    private fun shelf(
        taken: MutableStateFlow<List<TakenContribution<ModuleContribution>>>,
        compiledIn: List<TakenContribution<ModuleContribution>> = emptyList(),
    ) = SourceShelf(taken, { NoServices }, scope, compiledIn)

    private fun ids(shelf: SourceShelf) = shelf.views.value.map { it.id }

    @Test
    fun `a source that cannot link or throws is left out, and the others are listed and open`() {
        listOf(NoClassDefFoundError("hivens/catalogue/api/SomethingNewer"), AbstractMethodError("open"), IllegalStateException("boom")).forEach { failure ->
            val shelf = shelf(taken("jar.broken" to Broken("Broken", failure), "jar.ok" to Source("Healthy")))
            assertEquals(listOf("Healthy"), ids(shelf), "after ${failure.javaClass.simpleName}")
            assertNotNull(shelf.pack("Healthy"))
            assertNull(shelf.pack("Broken"))
        }
    }

    @Test
    fun `a source whose opening cannot link is not opened, and its neighbour is`() {
        val shelf = shelf(taken("jar.broken" to FailsToOpen("Broken", NoClassDefFoundError("x/Y")), "jar.ok" to Source("Healthy")))
        assertNull(shelf.pack("Broken"), "left out rather than thrown on")
        assertNull(shelf.listing("Broken"))
        assertNotNull(shelf.listing("Healthy"))
    }

    @Test
    fun `an error of the virtual machine is not taken for the source's fault`() {
        assertFailsWith<OutOfMemoryError> { shelf(taken("jar.broken" to Broken("Broken", OutOfMemoryError("heap")))) }
    }

    @Test
    fun `an id the launcher keeps for itself is refused`() {
        val shelf = shelf(taken("jar.a" to Source("Local"), "jar.b" to Source("Smartycraft"), "jar.c" to Source("Unknown"), "jar.d" to Source("Shelf")))
        assertEquals(listOf("Shelf"), ids(shelf))
        assertNull(shelf.listing("Local"), "and never installed from")
    }

    @Test
    fun `an id a source compiled in declares is refused from another module, whether or not that module is brought in`() {
        val compiled = listOf(TakenContribution<ModuleContribution>("nexira.source.compiled", Source("Compiled")))
        val notBrought = shelf(taken("jar.impostor" to Source("Compiled")), compiledIn = compiled)
        assertEquals(emptyList(), ids(notBrought), "the compiled source is not brought in, and the jar may not stand in for it")
        assertNull(notBrought.listing("Compiled"))

        val brought = shelf(taken("nexira.source.compiled" to compiled.single().item, "jar.impostor" to Source("Compiled")), compiledIn = compiled)
        assertEquals(listOf(SourceView("Compiled", "nexira.source.compiled", "Compiled")), brought.views.value, "its own module still brings it")
    }

    @Test
    fun `of two sources with one id the first brought in is taken and the second refused`() {
        val first = AtomicInteger()
        val second = AtomicInteger()
        val shelf = shelf(taken("jar.first" to Source("Shelf", first), "jar.second" to Source("Shelf", second)))
        assertEquals(listOf(SourceView("Shelf", "jar.first", "Shelf")), shelf.views.value)
        assertNotNull(shelf.listing("Shelf"))
        assertEquals(1 to 0, first.get() to second.get(), "the refused one is never opened")
    }

    @Test
    fun `an install and the list read the one listing the shelf opened`() {
        val opened = AtomicInteger()
        val shelf = shelf(taken("jar.shelf" to Source("Shelf", opened)))
        val listed = assertNotNull(shelf.pack("Shelf")).listing
        assertSame(listed, shelf.listing("Shelf"))
        assertEquals(1, opened.get())
    }

    @Test
    fun `a module that leaves lets its opened listing go, and is opened again when it comes back`() {
        val opened = AtomicInteger()
        val source = Source("Shelf", opened)
        val taken = taken("jar.shelf" to source)
        val shelf = shelf(taken)
        assertNotNull(shelf.pack("Shelf"))
        taken.value = emptyList()
        assertNull(shelf.pack("Shelf"))
        taken.value = listOf(TakenContribution("jar.shelf", source))
        assertNotNull(shelf.pack("Shelf"))
        assertEquals(2, opened.get(), "nothing of the module that left is kept to be handed back")
    }

    @Test
    fun `a source is opened with the services its own module sees`() {
        val asked = ArrayList<String>()
        val servicesOf = { module: String ->
            asked += module
            NoServices
        }
        val shelf = SourceShelf(taken("jar.shelf" to Source("Shelf"), "jar.other" to Source("Other")), servicesOf, scope)
        shelf.pack("Shelf")
        shelf.pack("Other")
        assertEquals(listOf("jar.shelf", "jar.other"), asked)
    }

    @Test
    fun `the generation moves when a module brings another instance of its source, and not otherwise`() {
        val taken = taken("jar.shelf" to Source("Shelf"))
        val shelf = shelf(taken)
        val before = shelf.generation.value
        taken.value = taken.value.toList()
        assertEquals(before, shelf.generation.value, "the same sources again are no change")
        taken.value = listOf(TakenContribution("jar.shelf", Source("Shelf")))
        assertEquals(before + 1, shelf.generation.value)
    }

    // ── Projects ────────────────────────────────────────────────────

    private class Projects(override val id: String) : ProjectSearchContribution {
        override val label: String = id
        override val types: Set<String> = setOf("mod")
        override fun open(services: LevelServices): ProjectSearcher = object : ProjectSearcher {
            override suspend fun search(type: String, query: String, filters: Set<SearchFilter>, offset: Int, limit: Int, sort: BrowseSort): List<ProjectHit> =
                listOf(ProjectHit("found-by-$id"))
            override suspend fun tags(): CatalogueTags = CatalogueTags()
        }
    }

    /** The engine's client for the one source it installs from, which a test must never reach. */
    private object Network : LevelServices {
        private val offline = HttpClientProvider { error("asked the network for a project another source found") }
        private val client = ModrinthClient(offline, TransferEngine(offline))

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> find(type: KClass<T>): T? = if (type == ModrinthClient::class) client as T else null
        override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
    }

    private val pack = ContentDestination.Pack.of(
        PackInstance("p", PackReference(PackOrigin.Local, "p"), "P", "p", 0),
        Path.of("/nonexistent"),
    )

    @Test
    fun `a project a third source found is not installed through the engine, and says why`() = runBlocking {
        val projects = ShelfProjects(shelf(taken("jar.third" to Projects("Third"))), Network)
        assertEquals("Third", projects.sourceOf("mod"))
        assertEquals(listOf("found-by-Third"), projects.search("mod", "", emptySet(), 0, 10, BrowseSort.Relevance).map { it.projectId })
        val refused = assertFailsWith<SourceCannotInstall> { projects.installNewest("mod", "found-by-Third", pack) }
        assertEquals("Third", refused.source)
    }

    @Test
    fun `the install that asks where offers nothing for a project of a source the engine cannot install from`() {
        val work = EngineInstallWork(Network)
        assertEquals(false, work.installsFrom("Third"))
        assertEquals(true, work.installsFrom(null), "a project page's project is the engine's own")
    }

    /** The engine as the install that asks where reaches it, installing from [ownSource] alone, and counting what it is asked. */
    private class Work(private val ownSource: String) : InstallWork {
        val asked = AtomicInteger()
        override suspend fun listVersions(projectId: String): List<ModrinthVersion> = emptyList<ModrinthVersion>().also { asked.incrementAndGet() }
        override suspend fun candidates(projectId: String, listing: List<ModrinthVersion>): List<InstallTargets.Candidate> = emptyList()
        override suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome = error("not installed here")
        override val installs: StateFlow<Map<String, InstallSnapshot>> = MutableStateFlow(emptyMap())
        override fun createPack(key: String, name: String, mcVersion: String, loader: String?) = Unit
        override fun dismiss(key: String) = Unit
        override suspend fun packOf(id: String): PackInstance? = null
        override val instancesDir: Path = Path.of("/nonexistent")
        override fun installsFrom(source: String?): Boolean = source == null || source == ownSource
    }

    @Test
    fun `the install that asks where says a third source's project cannot be installed, and asks nothing`() = runBlocking {
        val work = Work(ownSource = "Engine")
        val choices = InstallChoices(work, scope, scope)
        choices.choose(InstallSubject("found-by-Third", "Found", source = "Third"))
        val refused = withTimeout(5.seconds) { choices.view.first { it?.unsupported == true } }
        assertEquals(emptyList(), refused?.candidates, "no pack is offered")
        assertEquals(0, work.asked.get(), "the engine was never asked for the builds of a project it did not list")

        choices.choose(InstallSubject("engine-project", "Own", source = "Engine"))
        val offered = withTimeout(5.seconds) { choices.view.first { it?.subject?.projectId == "engine-project" && it.candidates != null } }
        assertEquals(false, offered?.unsupported)
        assertEquals(1, work.asked.get(), "its own source's project is read as before")
    }

    @Test
    fun `a search install refused by its source reads as that, not as a failed download`() = runTest {
        val search = ProjectSearch(
            kind = ProjectKind.Mod,
            destination = pack,
            search = { _, _, _, _ -> listOf(ProjectHit("x")) },
            installInto = { throw SourceCannotInstall("Third") },
            presentProjects = { emptySet() },
            hideInstalled = false,
            session = ProjectSnapshots { 0L },
            keyOf = { q -> ProjectSnapshots.Key("mod", q, BrowseSort.Relevance, emptySet(), "p", false) },
            contentStamp = { null },
            changed = {},
        )
        search.install("x")
        assertEquals(InstallProblem.SourceCannotInstall, search.problems["x"])
    }

    // ── Browse ──────────────────────────────────────────────────────

    /** A list that changes between the view's first reading and its collector's start is still shown. */
    @Test
    fun `a change of the sources as Browse is made is not lost`() = runTest {
        val a = SourceView("A", "jar.a", "a")
        val b = SourceView("B", "jar.b", "b")
        val sources = MutableStateFlow(listOf(a))
        val window = TestScope(StandardTestDispatcher(testScheduler))
        val browse = BrowseState(sources, { null }, Path.of("/nonexistent"), window)
        sources.value = listOf(a, b)
        window.advanceUntilIdle()
        assertEquals(listOf(a, b), browse.view.value.sources)
        val _ = withTimeout(5.seconds) { browse.view.first { it.sources.size == 2 } }
        window.cancel()
    }
}
