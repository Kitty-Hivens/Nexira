package hivens.widget.api

import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WidgetScopeTest {

    /** A source that counts how often a level made one and knows whether it was closed. */
    private class Made(val at: ScopeLevel) : WidgetDataSource<ScopeLevel>, AutoCloseable {
        override val state: StateFlow<ScopeLevel> = MutableStateFlow(at)
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class Service

    private val appKey = SourceKey<String>("test.app")
    private val windowKey = SourceKey<ScopeLevel>("test.window", ScopeLevel.Window)
    private val pageKey = SourceKey<String>("test.page", ScopeLevel.Page)

    private val made = ArrayList<Made>()

    private fun registry() = WidgetDataRegistry().apply {
        register(appKey, flowSource(MutableStateFlow("app")))
        registerFactory(windowKey) { context -> Made(context.level).also { made += it } }
    }

    private fun app(registry: WidgetDataRegistry = registry(), vararg modules: Module): WidgetScope {
        val koin = koinApplication {
            modules(module { single { WidgetScope.app(registry, this, Dispatchers.Unconfined) } }, *modules)
        }.koin
        return koin.get()
    }

    @Test
    fun `a level makes its source only when the key is first read, and once`() {
        val window = app().child(ScopeLevel.Window)
        assertTrue(made.isEmpty(), "nothing is made before anybody reads the key")
        val first = window.find(windowKey)
        val second = window.find(windowKey)
        assertEquals(1, made.size)
        assertSame(first, second)
    }

    @Test
    fun `two levels of one kind make one source each`() {
        val app = app()
        val a = app.child(ScopeLevel.Window)
        val b = app.child(ScopeLevel.Window)
        assertNotSame(a.find(windowKey), b.find(windowKey))
        assertEquals(2, made.size)
    }

    @Test
    fun `a key is read at the level it declares, from below it`() {
        val window = app().child(ScopeLevel.Window)
        val page = window.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertSame(window.find(windowKey), page.find(windowKey))
        assertEquals("app", page.current(appKey))
    }

    @Test
    fun `a key whose level is not above the caller is missing, not an error`() {
        val app = app()
        val window = app.child(ScopeLevel.Window)
        assertNull(app.find(windowKey))
        assertNull(window.find(pageKey))
        assertNull(window.current(pageKey))
        assertNull(window.nearest(ScopeLevel.Page))
        assertTrue(made.isEmpty(), "a miss makes nothing anywhere")
    }

    @Test
    fun `a page does not see what its sibling was given`() {
        val composition = app().child(ScopeLevel.Window).child(ScopeLevel.Composition)
        val first = composition.child(ScopeLevel.Page)
        val second = composition.child(ScopeLevel.Page)
        first.provide(pageKey, "first")
        assertEquals("first", first.current(pageKey))
        assertNull(second.current(pageKey))
    }

    @Test
    fun `closing a level takes everything below it along`() {
        val window = app().child(ScopeLevel.Window)
        val composition = window.child(ScopeLevel.Composition)
        val page = composition.child(ScopeLevel.Page)
        page.provide(pageKey, "here")
        val windowSource = window.find(windowKey) as Made
        window.close()
        assertTrue(window.closed && composition.closed && page.closed)
        assertTrue(windowSource.closed, "the source the window made is closed with it")
        assertFalse(page.coroutineScope.isActive, "a page's coroutines stop with the window")
        assertTrue(page.services.closed && window.services.closed, "the Koin scopes go too")
        assertNull(page.find(pageKey), "a closed level answers nothing")
        assertFailsWith<IllegalStateException> { window.child(ScopeLevel.Composition) }
    }

    @Test
    fun `closing a level leaves its parent and siblings alone`() {
        val app = app()
        val a = app.child(ScopeLevel.Window)
        val b = app.child(ScopeLevel.Window)
        val kept = b.find(windowKey) as Made
        a.close()
        assertFalse(b.closed)
        assertFalse(kept.closed)
        assertFalse(app.closed)
        assertTrue(app.coroutineScope.isActive)
    }

    @Test
    fun `a level sits only inside one declared before it`() {
        val window = app().child(ScopeLevel.Window)
        assertFailsWith<IllegalArgumentException> { window.child(ScopeLevel.Window) }
        assertFailsWith<IllegalArgumentException> { window.child(ScopeLevel.App) }
        // Skipping a level is allowed: an owner with no composition of its own can hold pages directly.
        assertEquals(ScopeLevel.Page, window.child(ScopeLevel.Page).level)
    }

    @Test
    fun `a second provision replaces the first, and the first one's withdrawal leaves it`() {
        val page = app().child(ScopeLevel.Page)
        val first = page.provide(pageKey, "first")
        val second = page.provide(pageKey, "second")
        first.close()
        assertEquals("second", page.current(pageKey))
        second.close()
        assertNull(page.current(pageKey))
    }

    @Test
    fun `a provision is refused where the key does not live`() {
        val app = app()
        val window = app.child(ScopeLevel.Window)
        assertFailsWith<IllegalArgumentException> { window.provide(pageKey, "nowhere") }
        assertFailsWith<IllegalArgumentException> { app.provide(appKey, "twice") }
        assertFailsWith<IllegalArgumentException> { window.provide(windowKey, ScopeLevel.Window) }
    }

    @Test
    fun `a service declared for a level is one per level and closed with it`() {
        val app = app(modules = arrayOf(module { levelServices(ScopeLevel.Window) { scoped { Service() } } }))
        val a = app.child(ScopeLevel.Window)
        val b = app.child(ScopeLevel.Window)
        val fromA = a.services.get<Service>()
        assertSame(fromA, a.services.get<Service>())
        assertNotSame(fromA, b.services.get<Service>())
        val page = a.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertSame(fromA, page.services.get<Service>(), "a page below the window gets the window's")
        assertNull(app.services.getOrNull<Service>(), "the app has none of its own")
    }

    @Test
    fun `an app value is still answered through the registry's own accessors`() {
        val registry = registry()
        assertEquals("app", registry.current(appKey))
        assertNull(registry.find(windowKey), "a factory is no value")
        assertEquals(setOf("test.app", "test.window"), registry.keys())
    }

    @Test
    fun `the registry refuses a value below the app and a duplicate factory`() {
        val registry = registry()
        assertFailsWith<IllegalArgumentException> { registry.register(pageKey, flowSource(MutableStateFlow("x"))) }
        assertFailsWith<IllegalArgumentException> { registry.registerFactory(windowKey) { Made(it.level) } }
        assertFailsWith<IllegalArgumentException> { registry.registerFactory(appKey) { flowSource(MutableStateFlow("y")) } }
    }

    @Test
    fun `a factory registered for one level answers no key claiming another`() {
        val app = app()
        val window = app.child(ScopeLevel.Window)
        val sameIdOtherLevel = SourceKey<ScopeLevel>("test.window", ScopeLevel.Page)
        assertNull(window.child(ScopeLevel.Page).find(sameIdOtherLevel))
    }

    @Test
    fun `a page level stays while its entry is in the history or held`() {
        val composition = app().child(ScopeLevel.Window).child(ScopeLevel.Composition)
        val pages = composition.pages
        assertSame(pages, composition.pages, "one table per level")
        val home = pages.levelFor("home")
        val mod = pages.levelFor("mod")
        assertSame(home, pages.levelFor("home"))
        pages.keep(setOf("home", "mod"))

        // A switch drops "mod" from the history while it is still fading out.
        val fading = pages.hold("mod")
        pages.keep(setOf("home"))
        assertFalse(mod.closed, "content still on screen keeps its level")
        fading.close()
        assertTrue(mod.closed)
        assertEquals(setOf("home"), pages.alive)

        // Stepping away keeps an entry that is still in the history.
        pages.hold("home").close()
        assertFalse(home.closed)

        composition.close()
        assertTrue(home.closed, "the pages go with the composition that kept them")
        assertFailsWith<IllegalStateException> { pages.levelFor("home") }
    }

    /**
     * One frame, in the order Compose applies it: the leaving page's hold is
     * released before the arriving page's effects run, and the history the owner
     * last kept does not name the arrival yet.
     */
    @Test
    fun `a page arriving in the frame another leaves survives the sweep`() {
        val pages = app().child(ScopeLevel.Window).child(ScopeLevel.Composition).pages
        pages.levelFor("old")
        val leaving = pages.hold("old")
        pages.keep(setOf("old"))

        // The switch: "new" composes, then "old" finishes leaving, then keep() runs.
        val arriving = pages.levelFor("new")
        leaving.close()
        assertFalse(arriving.closed, "made since the last keep, so the sweep spares it")
        pages.keep(setOf("new"))
        assertFalse(arriving.closed)
        assertEquals(setOf("new"), pages.alive)
    }

    @Test
    fun `a hold counts from the moment it is made`() {
        val pages = app().child(ScopeLevel.Window).pages
        val level = pages.levelFor("a")
        val hold = pages.hold("a")
        pages.keep(emptySet())
        assertFalse(level.closed, "held before any effect could run")
        hold.onAbandoned()
        assertTrue(level.closed, "an abandoned composition releases what it held")
        hold.close()
    }

    @Test
    fun `an owner installs what it hands a page before anything reads it`() {
        val pages = app().child(ScopeLevel.Window).pages
        val level = pages.levelFor("mod") { provide(pageKey, "opened with") }
        assertEquals("opened with", level.current(pageKey))
        pages.levelFor("mod") { error("installs only once, into the level it made") }
    }

    @Test
    fun `one page that fails to close does not stop the sweep or reach the owner`() {
        val failing = SourceKey<String>("test.failing", ScopeLevel.Page)
        val closedOnes = ArrayList<String>()
        val registry = registry().apply {
            registerFactory(failing) {
                object : WidgetDataSource<String>, AutoCloseable {
                    override val state: StateFlow<String> = MutableStateFlow("x")
                    override fun close() {
                        closedOnes += "failing"
                        error("this source cannot close")
                    }
                }
            }
        }
        val pages = app(registry).child(ScopeLevel.Window).pages
        val bad = pages.levelFor("bad")
        val good = pages.levelFor("good")
        bad.find(failing)
        pages.keep(emptySet())
        assertTrue(bad.closed && good.closed, "both went, the failure was logged")
        assertEquals(listOf("failing"), closedOnes)
        // A direct caller still hears about it.
        val direct = app(registry).child(ScopeLevel.Page)
        direct.find(failing)
        assertFailsWith<IllegalStateException> { direct.close() }
    }

    @Test
    fun `a factory that fails or reads its own key leaves the key missing, once`() {
        val selfish = SourceKey<String>("test.selfish", ScopeLevel.Page)
        val broken = SourceKey<String>("test.broken", ScopeLevel.Page)
        var attempts = 0
        val registry = registry().apply {
            registerFactory(selfish) { context -> context.find(selfish) ?: flowSource(MutableStateFlow("never")) }
            registerFactory(broken) {
                attempts++
                error("this factory always fails")
            }
        }
        val page = app(registry).child(ScopeLevel.Page)
        // The inner read is refused rather than recursing, so the outer one makes a source.
        assertEquals("never", page.current(selfish))
        assertSame(page.find(selfish), page.find(selfish), "and keeps it")
        assertNull(page.find(broken))
        assertNull(page.find(broken))
        assertEquals(1, attempts, "a failed factory is not run again on every read")
    }

    @Test
    fun `a factory may read other keys of its own level`() {
        val derived = SourceKey<String>("test.derived", ScopeLevel.Window)
        val registry = registry().apply {
            registerFactory(derived) { context -> flowSource(MutableStateFlow("from ${context.find(windowKey)?.state?.value}")) }
        }
        val window = app(registry).child(ScopeLevel.Window)
        assertEquals("from Window", window.current(derived))
    }

    @Test
    fun `a closed level answers nothing and takes no provision`() {
        val page = app().child(ScopeLevel.Page)
        page.provide(pageKey, "here")
        page.close()
        assertNull(page.find(pageKey))
        assertFailsWith<IllegalStateException> { page.provide(pageKey, "late") }
    }

    @Test
    fun `answers compare by content`() {
        assertEquals(ScopedSource.Present("a"), ScopedSource.Present("a"))
        assertEquals(ScopedSource.Missing(ScopeLevel.Page, true), ScopedSource.Missing(ScopeLevel.Page, true))
    }
}
