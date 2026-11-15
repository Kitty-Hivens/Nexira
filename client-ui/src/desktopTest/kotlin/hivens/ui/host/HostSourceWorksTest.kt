package hivens.ui.host

import hivens.catalogue.CatalogueKeysContribution
import hivens.catalogue.CatalogueModule
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.ProjectBuild
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.sources.SourcePackWork
import hivens.launcher.sources.SourceStatus
import hivens.launcher.sources.SourceWorks
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the host hands the engine the sources' work: through what the catalogue holds for a
 * pack's source id under the app level, as the launcher made that level. A source a module
 * compiled into the launcher brought with work answers with it, one that only lists, and
 * any source of a jar whatever it offers, answers with nothing to do, and an id no module
 * brings, or a document with no catalogue in it, answers missing.
 */
@OptIn(HostApi::class)
class HostSourceWorksTest {
    private val apps = ArrayList<WidgetScope>()

    @AfterTest
    fun tearDown() = apps.forEach { it.close() }

    private object NoPacks : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(emptyList())
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private open class Listing(private val origin: String) : PackListing {
        override suspend fun search(query: String, page: Int): List<CatalogueCard> = emptyList()
        override suspend fun details(packId: String): PackDetails = PackDetails(origin, packId, packId, "")
        override suspend fun versions(packId: String): List<ProjectBuild> = emptyList()
    }

    /** A listing whose module brought what the source does to its packs. */
    private class Working : Listing(KIT), SourcePackWork

    private val working = Working()

    private inner class Source(override val id: String, private val opens: PackListing) : PackSourceContribution {
        override val label: String = "source.name"
        override fun open(services: LevelServices): PackListing = opens
    }

    /** [compiled] is whether the modules that bring the two sources are compiled into the launcher. */
    private fun app(catalogue: Boolean, compiled: Boolean = true): WidgetScope {
        val taken = MutableStateFlow<List<TakenContribution<ModuleContribution>>>(
            listOf(TakenContribution("test.kit", Source(KIT, working)), TakenContribution("test.plain", Source(PLAIN, Listing(PLAIN)))),
        )
        val held: Map<KClass<*>, Any> = mapOf(
            TakenContributions::class to object : TakenContributions {
                override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = taken
                override val compiledIn: List<TakenContribution<ModuleContribution>> = if (compiled) taken.value else emptyList()
            },
            IPackRepository::class to NoPacks,
        )
        val engine = object : LevelServicesHost {
            override val services = object : LevelServices {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> find(type: KClass<T>): T? = held[type] as T?
                override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
            }
            override fun child(level: ScopeLevel): LevelServicesHost = this
            override fun close() = Unit
        }
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        if (catalogue) {
            assertIs<ModuleKeysResult.Registered>(registerModuleKeys(CatalogueModule.id, CatalogueKeysContribution, sources, commands, services, trusted = true))
        }
        return WidgetScope.app(sources, services.host(engine, Dispatchers.Unconfined), Dispatchers.Unconfined, commands, access = LauncherHostAccess)
            .also { apps += it }
    }

    @Test
    fun `the engine reaches each source's work through what the catalogue opened`() {
        val works = SourceWorks().apply { attach(sourceWorksOf(app(catalogue = true))) }

        assertSame(working, assertIs<SourceStatus.Loaded>(works.status(PackOrigin(KIT))).work, "the work the source's module brought")
        assertSame(SourceWorks.ListingOnly, assertIs<SourceStatus.Loaded>(works.status(PackOrigin(PLAIN))).work, "a source that only lists")
        assertEquals(SourceStatus.Missing(PackOrigin("Gone")), works.status(PackOrigin("Gone")))
        assertEquals(SourceStatus.None, works.status(PackOrigin.Local), "a pack of the player's own has no source to ask")
    }

    /** A jar cannot get install, update or reattach power, whatever its listing implements. */
    @Test
    fun `a source a jar brings is handed no work even where its listing offers some`() {
        val works = SourceWorks().apply { attach(sourceWorksOf(app(catalogue = true, compiled = false))) }

        assertSame(SourceWorks.ListingOnly, assertIs<SourceStatus.Loaded>(works.status(PackOrigin(KIT))).work)
    }

    @Test
    fun `with no catalogue brought in every source is missing`() {
        val works = SourceWorks().apply { attach(sourceWorksOf(app(catalogue = false))) }

        assertNull(works.work(PackOrigin(KIT)))
        assertEquals(SourceStatus.Missing(PackOrigin(KIT)), works.status(PackOrigin(KIT)))
    }

    private companion object {
        const val KIT = "Kit"
        const val PLAIN = "Plain"
    }
}
