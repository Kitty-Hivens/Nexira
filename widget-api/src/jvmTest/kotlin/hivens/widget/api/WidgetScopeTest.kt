package hivens.widget.api

import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private class Service(val name: String = "service")

    private val appKey = SourceKey<String>("test.app")
    private val windowKey = SourceKey<ScopeLevel>("test.window", ScopeLevel.Window)
    private val pageKey = SourceKey<String>("test.page", ScopeLevel.Page)

    private val made = ArrayList<Made>()

    private fun registry() = WidgetDataRegistry().apply {
        register(appKey, flowSource(MutableStateFlow("app")))
        registerFactory(windowKey) { context -> Made(context.level).also { made += it } }
    }

    private fun app(
        registry: WidgetDataRegistry = registry(),
        services: MemoryLevelServices = MemoryLevelServices.app(),
    ): WidgetScope = WidgetScope.app(registry, services, Dispatchers.Unconfined)

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
        assertTrue(page.memory.closed && composition.memory.closed && window.memory.closed, "their services go too")
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
    fun `each level gets its services from its parent's handle, for its own kind`() {
        val app = app()
        val window = app.child(ScopeLevel.Window)
        val page = window.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertEquals(listOf(ScopeLevel.Window), app.memory.children.map { it.level })
        assertEquals(ScopeLevel.Composition, window.memory.children.single().level)
        assertEquals(ScopeLevel.Page, page.memory.level)
    }

    @Test
    fun `closing a level closes the services it was given and leaves the app's to their owner`() {
        val app = app()
        val a = app.child(ScopeLevel.Window)
        val b = app.child(ScopeLevel.Window)
        a.close()
        assertTrue(a.memory.closed)
        assertFalse(b.memory.closed, "a sibling's services stay")
        app.close()
        assertTrue(b.memory.closed, "a window goes with the app and takes its services along")
        assertFalse(app.memory.closed, "the app's services belong to whoever handed them in")
    }

    @Test
    fun `a factory reads the services of the level it makes a source in`() {
        val derived = SourceKey<String>("test.service", ScopeLevel.Window)
        val registry = registry().apply {
            registerFactory(derived) { context -> flowSource(MutableStateFlow(context.services.get<Service>().name)) }
        }
        val services = MemoryLevelServices.app(mapOf(ScopeLevel.Window to listOf({ Service("per window") })))
        val app = app(registry, services)
        val window = app.child(ScopeLevel.Window)
        assertEquals("per window", window.current(derived))
        assertSame(window.services.find<Service>(), window.child(ScopeLevel.Page).services.find<Service>(), "a page sees the window's")
        assertNull(app.services.find<Service>(), "the app has none of its own")
        assertFailsWith<NoSuchElementException> { app.services.get<Service>() }
    }

    @Test
    fun `a level closes its children, what it made, its coroutines and its services in that order, and leaves its parent last`() {
        val events = ArrayList<String>()
        val logged = SourceKey<String>("test.logged", ScopeLevel.Window)
        val registry = registry().apply {
            registerFactory(logged) {
                object : WidgetDataSource<String>, AutoCloseable {
                    override val state: StateFlow<String> = MutableStateFlow("logged")
                    override fun close() {
                        events += "source"
                    }
                }
            }
        }
        lateinit var root: WidgetScope
        lateinit var window: WidgetScope
        val services = MemoryLevelServices.app(events = events) { closing ->
            if (closing.level == ScopeLevel.Window) events += "still held ${root.holds(window)}"
        }
        root = app(registry, services)
        window = root.child(ScopeLevel.Window)
        window.child(ScopeLevel.Page)
        window.find(logged)
        val job = assertNotNull(window.coroutineScope.coroutineContext[Job])
        job.invokeOnCompletion { events += "job" }
        window.close()
        assertEquals(listOf("services Page", "source", "job", "services Window", "still held true"), events)
        assertFalse(root.holds(window), "and it has left once its services are gone")
    }

    @Test
    fun `services that fail to close still let the level leave its parent, and are thrown with the other failures`() {
        val failing = SourceKey<String>("test.failing.window", ScopeLevel.Window)
        val registry = registry().apply {
            registerFactory(failing) {
                object : WidgetDataSource<String>, AutoCloseable {
                    override val state: StateFlow<String> = MutableStateFlow("x")
                    override fun close() = error("this source cannot close")
                }
            }
        }
        val app = app(registry, MemoryLevelServices.app(failClose = setOf(ScopeLevel.Window)))
        val window = app.child(ScopeLevel.Window)
        window.find(failing)
        val thrown = assertFailsWith<IllegalStateException> { window.close() }
        assertEquals("this source cannot close", thrown.message, "the first failure is the one thrown")
        assertEquals(listOf("the Window services cannot close"), thrown.suppressed.map { it.message })
        assertTrue(window.memory.closed)
        assertFalse(window.coroutineScope.isActive)
        assertFalse(app.holds(window), "it left its parent all the same")
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
