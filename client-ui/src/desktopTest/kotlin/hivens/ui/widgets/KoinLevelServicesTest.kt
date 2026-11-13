package hivens.ui.widgets

import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.find
import hivens.widget.api.get
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The launcher's level services: a Koin scope per level, linked up the tree, closed with its level. */
class KoinLevelServicesTest {

    private class PerWindow

    private class PerPage

    private class AppWide

    private class PerComposition

    private class NeedsPerPage(val page: PerPage)

    private fun koin(vararg modules: Module): Koin = koinApplication {
        val root = module {
            single { KoinLevelServicesHost.app(this) }
            single { WidgetScope.app(WidgetDataRegistry(), get<KoinLevelServicesHost>(), Dispatchers.Unconfined) }
        }
        modules(root, *modules)
    }.koin

    private fun app(vararg modules: Module): WidgetScope = koin(*modules).get()

    @Test
    fun `a service declared for a level is one per level and closed with it`() {
        val app = app(module { levelServices(ScopeLevel.Window) { scoped { PerWindow() } } })
        val a = app.child(ScopeLevel.Window)
        val b = app.child(ScopeLevel.Window)
        val fromA = a.services.get<PerWindow>()
        assertSame(fromA, a.services.get<PerWindow>())
        assertNotSame(fromA, b.services.get<PerWindow>())
        val page = a.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertSame(fromA, page.services.get<PerWindow>(), "a page below the window gets the window's")
        assertNull(app.services.find<PerWindow>(), "the app has none of its own")
        a.close()
        assertTrue(a.koinScope.closed && page.koinScope.closed, "the Koin scopes go with their levels")
        assertFalse(b.koinScope.closed, "a sibling's stays")
    }

    @Test
    fun `each level kind gets its own, and an app single is seen from every level`() {
        val app = app(
            module {
                single { AppWide() }
                levelServices(ScopeLevel.Window) { scoped { PerWindow() } }
                levelServices(ScopeLevel.Page) { scoped { PerPage() } }
            },
        )
        val window = app.child(ScopeLevel.Window)
        val first = window.child(ScopeLevel.Page)
        val second = window.child(ScopeLevel.Page)
        assertNull(window.services.find<PerPage>(), "a window has no page above it")
        assertNotSame(first.services.get<PerPage>(), second.services.get<PerPage>())
        assertSame(app.services.get<AppWide>(), first.services.get<AppWide>())
        assertSame(window.services.get<PerWindow>(), second.services.get<PerWindow>())
    }

    @Test
    fun `the app level borrows the root scope and never closes it`() {
        val koin = koin(module { levelServices(ScopeLevel.Window) { scoped { PerWindow() } } })
        val app = koin.get<WidgetScope>()
        assertTrue(app.koinScope.isRoot)
        val window = app.child(ScopeLevel.Window)
        app.close()
        assertTrue(window.koinScope.closed, "a window goes with the app")
        assertFalse(app.koinScope.closed, "the root lives as long as Koin does")
        koin.get<KoinLevelServicesHost>().close()
        assertFalse(app.koinScope.closed, "even when its handle is closed by hand")
    }

    @Test
    fun `a missing service is null to find and the contract's own failure to get`() {
        val window = app().child(ScopeLevel.Window)
        assertNull(window.services.find<PerWindow>())
        val thrown = assertFailsWith<NoSuchElementException> { window.services.get<PerWindow>() }
        assertIs<NoDefinitionFoundException>(thrown.cause, "Koin's own failure stays attached")
    }

    @Test
    fun `find is null once the level is closed, and a service that cannot be made fails as Koin's own`() {
        val app = app(
            module {
                levelServices(ScopeLevel.Window) { scoped { NeedsPerPage(get()) } }
                levelServices(ScopeLevel.Page) { scoped { PerPage() } }
                levelServices(ScopeLevel.Composition) { scoped { PerComposition() } }
            },
        )
        val window = app.child(ScopeLevel.Window)
        // Declared for the window, but made from a page service no window has.
        assertFailsWith<InstanceCreationException> { window.services.find<NeedsPerPage>() }
        assertFailsWith<InstanceCreationException> { window.services.get<NeedsPerPage>() }
        val composition = window.child(ScopeLevel.Composition)
        val services = composition.services
        assertSame(services.get<PerComposition>(), services.find<PerComposition>())
        composition.close()
        assertNull(services.find<PerComposition>(), "a closed level's services answer nothing")
    }

    @Test
    fun `only the root scope can be the app's`() {
        val koin = koinApplication { modules(module { scope(named("other")) { } }) }.koin
        val other = koin.createScope("test.other", named("other"))
        assertFailsWith<IllegalArgumentException> { KoinLevelServicesHost.app(other) }
    }

    @Test
    fun `the launcher refuses to draw a level whose services are not Koin's`() {
        val foreign = object : LevelServicesHost {
            override val services = object : LevelServices {
                override fun <T : Any> find(type: KClass<T>): T? = null
                override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
            }
            override fun child(level: ScopeLevel): LevelServicesHost = this
            override fun close() = Unit
        }
        val app = WidgetScope.app(WidgetDataRegistry(), foreign, Dispatchers.Unconfined)
        assertFailsWith<IllegalStateException> { app.child(ScopeLevel.Window).koinScope }
    }
}
