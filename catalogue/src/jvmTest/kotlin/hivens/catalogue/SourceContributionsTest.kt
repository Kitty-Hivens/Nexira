package hivens.catalogue

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackPageStatus
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.PackVisit
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectKind
import hivens.catalogue.api.SearchStatus
import hivens.catalogue.api.SourceFilter
import hivens.catalogue.api.SourceFilterOption
import hivens.catalogue.api.SourceOption
import hivens.catalogue.api.SourceOptionSet
import hivens.catalogue.api.SourceView
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.platform.PlatformPaths
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStore
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.get
import hivens.widget.api.registerModuleKeys
import hivens.widget.api.service
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The catalogue as an engine over the sources the modules bring: a source is in it by
 * being a module's contribution and nothing else, a module of the test's own the way a
 * third party would bring one, and with no source brought in the catalogue lists
 * nothing and nothing breaks.
 */
@OptIn(HostApi::class)
class SourceContributionsTest {

    private val dir: Path = Files.createTempDirectory("catalogue-sources-test")
    private val apps = ArrayList<WidgetScope>()

    @AfterTest
    fun tearDown() {
        apps.forEach { it.close() }
        dir.toFile().deleteRecursively()
    }

    private object NoPacks : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(emptyList())
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) = Unit
        override suspend fun delete(id: String) = Unit
    }

    /** What the shelf answers: two packs, an old one and a new one, and their pages. */
    private class ShelfListing : PackListing {
        override suspend fun search(query: String, page: Int): List<CatalogueCard> = listOf(
            CatalogueCard(SHELF, "old", "Old", "", facets = mapOf("era" to "dusty")),
            CatalogueCard(SHELF, "new", "New", "", facets = mapOf("era" to "new")),
        )
        override suspend fun details(packId: String): PackDetails = PackDetails(SHELF, packId, packId.uppercase(), "")
        override suspend fun versions(packId: String): List<ProjectBuild> = emptyList()
    }

    /** A settings domain of one module, for a test of whose a source is handed. */
    private class Prefs(id: String) : SettingsDomain<String>(id, String.serializer()) {
        override fun defaults(): String = "kept"
    }

    /**
     * The shelf module's own service, which its source answers through, made the way a
     * module makes one, and its own settings.
     */
    private object ShelfKeys : KeyContribution {
        override val id: String = "shelf.keys"
        override val label: String = "Shelf"
        override fun declare(keys: ModuleKeys) {
            keys.service(ScopeLevel.App) { _ -> ShelfListing() }
            keys.settings(SHELF_PREFS, SourceKey("shelf.prefs"), CommandKey("shelf.prefs.update"))
        }
    }

    /** Settings the catalogue module declares, which no source may open. Neither it nor the packs module has any of its own yet. */
    private object CatalogueSideKeys : KeyContribution {
        override val id: String = "catalogue.side"
        override val label: String = "Catalogue"
        override fun declare(keys: ModuleKeys) {
            keys.settings(CATALOGUE_PREFS, SourceKey("catalogue.test.prefs"), CommandKey("catalogue.test.prefs.update"))
        }
    }

    /** The settings the host keeps, in memory. */
    private object MemoryStores : SettingsStores {
        private val held = HashMap<String, SettingsStore<*>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> of(domain: SettingsDomain<T>): SettingsStore<T> = synchronized(held) {
            held.getOrPut(domain.id) {
                object : SettingsStore<T> {
                    override val value = MutableStateFlow(domain.defaults())
                    override val readOnly = false
                    override fun update(change: SettingsChange<T>) {
                        value.value = change.apply(value.value)
                    }
                }
            } as SettingsStore<T>
        }
    }

    /** A source that, as it is opened, tries its own module's settings and the catalogue's. */
    private class Prying(private val tried: MutableMap<String, Boolean>) : PackSourceContribution {
        override val id: String = SHELF
        override val label: String = "shelf.name"
        override fun open(services: LevelServices): PackListing {
            val stores = services.get<SettingsStores>()
            tried["own"] = runCatching { stores.of(SHELF_PREFS) }.isSuccess
            tried["catalogue"] = runCatching { stores.of(CATALOGUE_PREFS) }.isSuccess
            return services.get<ShelfListing>()
        }
    }

    /** The shelf as a source, declared the way any module declares one. */
    private class Shelf(private val opened: AtomicInteger) : PackSourceContribution {
        override val id: String = SHELF
        override val label: String = "shelf.name"
        override val paged: Boolean = false
        override val filters: List<SourceFilter> =
            listOf(SourceFilter("era", "shelf.era", listOf(SourceFilterOption("dusty", "shelf.dusty"), SourceFilterOption("new", "shelf.new"))))

        override fun open(services: LevelServices): PackListing {
            opened.incrementAndGet()
            return services.get<ShelfListing>()
        }
    }

    private val taken = MutableStateFlow<List<TakenContribution<ModuleContribution>>>(emptyList())

    /** The engine's services the catalogue reads, and the contributions the host hands over. */
    private inner class Engine : LevelServicesHost {
        private val held: Map<KClass<*>, Any> = mapOf(
            TakenContributions::class to object : TakenContributions {
                override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = taken
            },
            IPackRepository::class to NoPacks,
            SettingsStores::class to MemoryStores,
            PlatformPaths::class to PlatformPaths(osName = "linux", home = dir, bootstrapDataDir = { null }, env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null }),
        )
        override val services = object : LevelServices {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> find(type: KClass<T>): T? = held[type] as T?
            override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
        }
        override fun child(level: ScopeLevel): LevelServicesHost = this
        override fun close() = Unit
    }

    /** The catalogue module, trusted as the launcher trusts it, and the shelf module as a jar, trusted with nothing. */
    private fun app(): WidgetScope {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys(CatalogueModule.id, CatalogueKeysContribution, sources, commands, services, trusted = true))
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys(CatalogueModule.id, CatalogueSideKeys, sources, commands, services, trusted = true))
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys(SHELF_MODULE, ShelfKeys, sources, commands, services, trusted = false))
        return WidgetScope.app(sources, services.host(Engine(), Dispatchers.Unconfined), Dispatchers.Unconfined, commands).also { apps += it }
    }

    private fun <T> await(what: String, flow: StateFlow<T>, until: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(5.seconds) { flow.first(until) }
        } catch (e: Exception) {
            throw AssertionError("$what never came: last was ${flow.value}", e)
        }
    }

    @Test
    fun `with no source brought in the catalogue lists nothing, and nothing breaks`() {
        val window = app().child(ScopeLevel.Window)
        val browse = assertNotNull(window.find(CatalogueKeys.Browse)).state
        assertEquals(emptyList(), browse.value.sources)
        assertEquals("", browse.value.origin, "no source to list from")
        assertNull(browse.value.type, "and no project search to run for packs")

        assertNotNull(window.findCommand(CatalogueKeys.ShowResults)).run("browse")
        val results = assertNotNull(window.find(CatalogueKeys.Results)).state
        assertEquals(SearchStatus.NoSource, await("the answer", results) { it.status != SearchStatus.Loading }.status, "nobody to ask, which is not an empty answer")

        assertNotNull(window.findCommand(CatalogueKeys.SetKind)).run(ProjectKind.Mod)
        assertEquals(SearchStatus.NoSource, await("the project answer", results) { it.question?.kind == ProjectKind.Mod && it.status != SearchStatus.Loading }.status)

        val page = window.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertNotNull(page.findCommand(CatalogueKeys.LoadTags)).run(Unit)
        assertNull(assertNotNull(page.find(CatalogueKeys.Tags)).state.value, "no project source, no lists, and nothing thrown")
    }

    @Test
    fun `a source a module brings is listed with its own filter, and its packs are searched and filtered by it`() {
        val opened = AtomicInteger()
        taken.value = listOf(TakenContribution(SHELF_MODULE, Shelf(opened)))
        val app = app()
        val window = app.child(ScopeLevel.Window)
        val browse = assertNotNull(window.find(CatalogueKeys.Browse)).state
        val shelf = browse.value.sources.single()
        assertEquals(SourceView(SHELF, SHELF_MODULE, "shelf.name", filters = Shelf(opened).filters), shelf)
        assertEquals(listOf(shelf), assertNotNull(app.find(CatalogueKeys.Sources)).state.value, "the app's list of sources says the same")
        assertEquals(SHELF, browse.value.origin, "the one source there is is the one listed from")

        assertNotNull(window.findCommand(CatalogueKeys.ShowResults)).run("browse")
        val results = assertNotNull(window.find(CatalogueKeys.Results)).state
        val listed = await("the shelf's packs", results) { it.status == SearchStatus.Loaded }.packs
        assertEquals(listOf("old", "new"), listed.map { it.id })
        assertEquals(1, opened.get(), "opened once for the app")

        assertNotNull(window.findCommand(CatalogueKeys.ToggleSourceOption)).run(SourceOption(SHELF, "era", "dusty"))
        val view = await("the option off", browse) { it.hidden.isNotEmpty() }
        assertEquals(listOf("new"), listed.filter(view::shows).map { it.id }, "the dusty pack is left out where the list is shown")
        assertTrue(results.value.packs.size == 2, "and the list itself is kept whole, so taking it back asks the source nothing")
    }

    @Test
    fun `a source whose module leaves goes with it, and comes back opened anew`() {
        val opened = AtomicInteger()
        taken.value = listOf(TakenContribution(SHELF_MODULE, Shelf(opened)))
        val window = app().child(ScopeLevel.Window)
        val browse = assertNotNull(window.find(CatalogueKeys.Browse)).state
        assertNotNull(window.findCommand(CatalogueKeys.SetOrigin)).run(SHELF)
        assertNotNull(window.findCommand(CatalogueKeys.ShowResults)).run("browse")
        val results = assertNotNull(window.find(CatalogueKeys.Results)).state
        await("the shelf's packs", results) { it.status == SearchStatus.Loaded }

        taken.value = emptyList()
        val gone = await("the source gone", browse) { it.sources.isEmpty() }
        assertEquals(SHELF, gone.pickedOrigin, "the person's pick is kept for the module's return")
        assertEquals("", gone.origin)

        taken.value = listOf(TakenContribution(SHELF_MODULE, Shelf(opened)))
        assertEquals(SHELF, await("the source back", browse) { it.sources.isNotEmpty() }.origin)
        assertNotNull(window.findCommand(CatalogueKeys.RetryResults)).run(Unit)
        await("listed again", results) { it.status == SearchStatus.Loaded && it.question?.origin == SHELF }
        assertEquals(2, opened.get(), "the module's new instance of the source is opened, not the old one reused")
    }

    @Test
    fun `a pack page of a source no module brings says it could not be read, and throws nothing`() {
        val page = app().child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertNotNull(page.findCommand(CatalogueKeys.ShowPack)).run(PackVisit("Curseforge", "cozy", "visit"))
        val view = assertNotNull(page.find(CatalogueKeys.PackPage)).state
        val shown = await("the page's answer", view) { it?.status is PackPageStatus.Failed }
        assertEquals("Curseforge", shown?.origin)
        assertFalse(shown?.status is PackPageStatus.Loaded)
    }

    @Test
    fun `a source is opened with its own module's settings and never the catalogue's`() {
        val tried = HashMap<String, Boolean>()
        taken.value = listOf(TakenContribution(SHELF_MODULE, Prying(tried)))
        val app = app()
        assertNotNull(app.services.get<SourceShelf>().listing(SHELF))
        assertEquals(mapOf("own" to true, "catalogue" to false), tried)
    }

    @Test
    fun `an option of a source's filter is listed and left out by the command that sets it`() {
        taken.value = listOf(TakenContribution(SHELF_MODULE, Shelf(AtomicInteger())))
        val window = app().child(ScopeLevel.Window)
        val browse = assertNotNull(window.find(CatalogueKeys.Browse)).state
        val dusty = SourceOption(SHELF, "era", "dusty")
        val set = assertNotNull(window.findCommand(CatalogueKeys.SetSourceOption))
        set.run(SourceOptionSet(dusty, listed = false))
        assertFalse(await("off", browse) { dusty in it.hidden }.lists(dusty))
        set.run(SourceOptionSet(dusty, listed = false))
        assertEquals(setOf(dusty), browse.value.hidden, "setting it off again leaves it off")
        set.run(SourceOptionSet(dusty, listed = true))
        assertTrue(await("on", browse) { dusty !in it.hidden }.lists(dusty))
    }

    @Test
    fun `a pack page read before its source left keeps what it read`() {
        taken.value = listOf(TakenContribution(SHELF_MODULE, Shelf(AtomicInteger())))
        val page = app().child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertNotNull(page.findCommand(CatalogueKeys.ShowPack)).run(PackVisit(SHELF, "old", "visit"))
        val view = assertNotNull(page.find(CatalogueKeys.PackPage)).state
        val read = await("the page", view) { it?.status is PackPageStatus.Loaded }?.details

        taken.value = emptyList()
        assertNotNull(page.findCommand(CatalogueKeys.RetryPack)).run(Unit)
        runBlocking { delay(300) }
        assertEquals(PackPageStatus.Loaded(assertNotNull(read)), view.value?.status)
    }

    /** A listing that goes on answering for as long as it is read, and says whether anybody reads it. */
    private class Watched(private val name: String) : PackListing {
        val reading = AtomicInteger()
        val details = AtomicInteger()
        override suspend fun search(query: String, page: Int): List<CatalogueCard> = listOf(CatalogueCard(SHELF, name, name, ""))
        override fun searchStream(query: String, page: Int): Flow<List<CatalogueCard>> = flow {
            reading.incrementAndGet()
            try {
                emit(search(query, page))
                awaitCancellation()
            } finally {
                reading.decrementAndGet()
            }
        }
        override suspend fun details(packId: String): PackDetails = PackDetails(SHELF, packId, name, "")
        override fun detailsStream(packId: String): Flow<PackDetails> = flow {
            details.incrementAndGet()
            try {
                emit(details(packId))
                awaitCancellation()
            } finally {
                details.decrementAndGet()
            }
        }
        override suspend fun versions(packId: String): List<ProjectBuild> = emptyList()
    }

    /** A source that answers with [listing], one instance per load of its module. */
    private class Loaded(private val listing: PackListing) : PackSourceContribution {
        override val id: String = SHELF
        override val label: String = "shelf.name"
        override val paged: Boolean = false
        override fun open(services: LevelServices): PackListing = listing
    }

    /** A jar loaded again carries the same source by the same id, and only its new code may go on running. */
    @Test
    fun `a module loaded again with the same source has its new listing read, and the old one let go`() {
        val old = Watched("old")
        taken.value = listOf(TakenContribution(SHELF_MODULE, Loaded(old)))
        val window = app().child(ScopeLevel.Window)
        assertNotNull(window.findCommand(CatalogueKeys.ShowResults)).run("browse")
        val results = assertNotNull(window.find(CatalogueKeys.Results)).state
        await("the old listing", results) { it.packs.map { p -> p.id } == listOf("old") }
        val page = window.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertNotNull(page.findCommand(CatalogueKeys.ShowPack)).run(PackVisit(SHELF, "p", "visit"))
        val view = assertNotNull(page.find(CatalogueKeys.PackPage)).state
        await("the old page", view) { it?.details?.title == "old" }

        val fresh = Watched("new")
        taken.value = listOf(TakenContribution(SHELF_MODULE, Loaded(fresh)))
        await("the new listing", results) { it.packs.map { p -> p.id } == listOf("new") }
        await("the new page", view) { it?.details?.title == "new" }
        runBlocking { withTimeout(5.seconds) { while (old.reading.get() != 0 || old.details.get() != 0) delay(20) } }
        assertEquals(1, fresh.reading.get(), "the new one is read")
        assertEquals(1, fresh.details.get())
    }

    private companion object {
        const val SHELF = "Shelf"
        const val SHELF_MODULE = "test.shelf"
        val SHELF_PREFS = Prefs("shelf.prefs")
        val CATALOGUE_PREFS = Prefs("catalogue.test.prefs")
    }
}
