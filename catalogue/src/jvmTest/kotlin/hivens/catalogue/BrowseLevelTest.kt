package hivens.catalogue

import hivens.catalogue.api.CatalogueKeys
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.platform.PlatformPaths
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.TakenContributions
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.api.find
import hivens.widget.api.get
import hivens.widget.api.TakenContribution
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What Browse searches, as the catalogue module brings it: one per window, made by the
 * window's level from the module's declaration, and read the same anywhere below it.
 */
@OptIn(HostApi::class)
class BrowseLevelTest {

    private val dir: Path = Files.createTempDirectory("catalogue-level-test")

    @AfterTest
    fun tearDown() = Files.deleteIfExists(dir).let { }

    private object NoPacks : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(emptyList())
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) = Unit
        override suspend fun delete(id: String) = Unit
    }

    /** The engine's services the window's browse is made from, and nothing else. */
    private inner class Engine : LevelServicesHost {
        private val held: Map<KClass<*>, Any> = mapOf(
            TakenContributions::class to object : TakenContributions {
                override val all = MutableStateFlow(emptyList<TakenContribution<ModuleContribution>>())
            },
            IPackRepository::class to NoPacks,
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

    private fun app(): WidgetScope {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        val result = registerModuleKeys(CatalogueModule.id, CatalogueKeysContribution, sources, commands, services, trusted = true)
        assertIs<ModuleKeysResult.Registered>(result)
        return WidgetScope.app(sources, services.host(Engine(), Dispatchers.Unconfined), Dispatchers.Unconfined, commands)
    }

    @Test
    fun `two windows get two controllers, and everything in a window gets its own`() {
        val app = app()
        val first = app.child(ScopeLevel.Window)
        val second = app.child(ScopeLevel.Window)
        val browse = first.services.get<BrowseState>()
        assertSame(browse, first.services.get<BrowseState>())
        assertNotSame(browse, second.services.get<BrowseState>())
        val page = first.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertSame(browse, page.services.get<BrowseState>())
        assertNull(app.services.find<BrowseState>(), "no window, no browse")
        app.close()
    }

    /** What a widget reads is answered by the window it sits in, at any depth below it. */
    @Test
    fun `an injection resolves from the nearest window`() {
        val app = app()
        val first = app.child(ScopeLevel.Window)
        val second = app.child(ScopeLevel.Window)
        val inFirst = assertNotNull(first.find(CatalogueKeys.Browse))
        val inFirstPage = first.child(ScopeLevel.Composition).child(ScopeLevel.Page).find(CatalogueKeys.Browse)
        assertSame(inFirst, inFirstPage)
        assertNotSame(inFirst, second.find(CatalogueKeys.Browse))
        assertSame(first.services.get<BrowseState>().view, inFirst.state)
        assertNull(app.find(CatalogueKeys.Browse), "a window key has no answer above every window")
        app.close()
    }

    /** A crumb in a second window names a page the first one read. */
    @Test
    fun `what a page was called is kept for the app and read the same from every window`() {
        val app = app()
        val first = app.child(ScopeLevel.Window)
        val second = app.child(ScopeLevel.Window)
        first.services.get<OpenProjects>().name("pack:Shelf:Industrial", "Industrial")

        assertEquals("Industrial", second.current(CatalogueKeys.Names)?.get("pack:Shelf:Industrial"))
        assertEquals("Industrial", app.current(CatalogueKeys.Names)?.get("pack:Shelf:Industrial"))
        app.close()
    }
}
