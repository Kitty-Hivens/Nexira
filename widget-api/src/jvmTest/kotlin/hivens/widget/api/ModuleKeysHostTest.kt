package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A module's keys and services, registered through its key contribution the way the
 * host brings them in: all of a module's or none of them, made where their level is,
 * closed with it, and missing rather than broken once the module is gone.
 */
class ModuleKeysHostTest {

    private val greeting = SourceKey<String>("test.greeting")
    private val perWindow = SourceKey<Int>("test.window", ScopeLevel.Window)
    private val shout = CommandKey<String>("test.shout")

    /** Something a module makes once per level of a kind and lets go of with it. */
    private class Counter : AutoCloseable {
        val count = AtomicInteger()
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class Contribution(private val body: (ModuleKeys) -> Unit) : KeyContribution {
        override val id = "test.keys"
        override val label = "Test keys"
        override fun declare(keys: ModuleKeys) = body(keys)
    }

    private val shouted = CopyOnWriteArrayList<String>()

    private val module = Contribution { keys ->
        keys.service(ScopeLevel.App) { Counter() }
        keys.service(ScopeLevel.Window) { context -> WindowThing(context.services.get<Counter>()) }
        keys.source(greeting, sample = { "sample" }) { app -> flowSource(MutableStateFlow("hello ${app.services.get<Counter>().count.incrementAndGet()}")) }
        keys.source(perWindow) { window -> flowSource(MutableStateFlow(System.identityHashCode(window.services.get<WindowThing>()))) }
        keys.command(shout) { command { shouted += it } }
    }

    private class WindowThing(val app: Counter) : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class Tree(contribution: KeyContribution, live: () -> Boolean = { true }) {
        val sources: WidgetDataRegistry = WidgetDataRegistry()
        val commands: WidgetCommandRegistry = WidgetCommandRegistry()
        val services: ContributedServices = ContributedServices()
        val result: ModuleKeysResult = registerModuleKeys("test", { contribution.takeIf { live() } }, sources, commands, services)
        val fault: String? = (result as? ModuleKeysResult.Refused)?.reason
        val host: LevelServicesHost = services.host(MemoryLevelServices.app(), Dispatchers.Unconfined)
        val app: WidgetScope = WidgetScope.app(sources, host, Dispatchers.Unconfined, commands)
    }

    @Test
    fun `a module's keys answer at their levels, with the samples it gave`() {
        val tree = Tree(module)
        assertNull(tree.fault)
        assertEquals("hello 1", tree.app.current(greeting))
        assertEquals("sample", tree.sources.sampleOf(greeting.id)?.invoke())
        assertEquals(KeyOrigin.Factory, tree.app.declarationOf(perWindow.id)?.origin)
        assertNull(tree.app.find(perWindow), "a window key has no answer above a window")
        val window = tree.app.child(ScopeLevel.Window)
        assertNotNull(window.current(perWindow))
        assertNotNull(window.findCommand(shout)).run("hi")
        assertEquals(listOf("hi"), shouted.toList())
    }

    @Test
    fun `a module's service is one per level of its kind, seen from below, and closed with its level`() {
        val tree = Tree(module)
        val first = tree.app.child(ScopeLevel.Window)
        val second = tree.app.child(ScopeLevel.Window)
        val page = first.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        val firstThing = assertNotNull(first.services.find<WindowThing>())
        assertSame(firstThing, page.services.find<WindowThing>(), "a level below sees its window's")
        val secondThing = assertNotNull(second.services.find<WindowThing>())
        assertTrue(firstThing !== secondThing, "two windows, two of them")
        assertSame(firstThing.app, secondThing.app, "and the app's is one")
        assertNull(tree.app.services.find<WindowThing>(), "the app is above every window")

        first.close()
        assertTrue(firstThing.closed)
        assertFalse(secondThing.closed)

        val appCounter = tree.app.hostServices.get<Counter>()
        tree.host.close()
        assertTrue(appCounter.closed, "the app's go when the host closes the app's handle")
    }

    @Test
    fun `the host's own service of a type wins over a module's`() {
        val mine = Counter()
        val sources = WidgetDataRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("test", module, sources, WidgetCommandRegistry(), services))
        val host = services.host(MemoryLevelServices.app(mapOf(ScopeLevel.App to listOf({ mine }))), Dispatchers.Unconfined)
        assertSame(mine, host.hostServices.get<Counter>())
    }

    @Test
    fun `a module that declares an id already answered registers nothing at all`() {
        val sources = WidgetDataRegistry().apply { register(greeting, flowSource(MutableStateFlow("the host's"))) }
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        val fault = assertIs<ModuleKeysResult.Refused>(registerModuleKeys("test", module, sources, commands, services)).reason
        assertTrue(greeting.id in fault, fault)
        assertNull(commands.declarationOf(shout.id), "the module's other keys did not go in either")
        assertNull(sources.declarationOf(perWindow.id))
        assertNull(services.ownerOf(Counter::class))
    }

    @Test
    fun `a module whose declaration throws registers nothing and says why`() {
        val sources = WidgetDataRegistry()
        val result = registerModuleKeys(
            "test",
            Contribution { keys ->
                keys.source(greeting) { flowSource(MutableStateFlow("x")) }
                error("half way")
            },
            sources,
            WidgetCommandRegistry(),
            ContributedServices(),
        )
        val refused = assertIs<ModuleKeysResult.Refused>(result)
        assertTrue("half way" in refused.reason, refused.reason)
        assertNotNull(refused.cause, "the module's own failure, for the host to pin on it")
        assertNull(sources.declarationOf(greeting.id))
    }

    @Test
    fun `two modules cannot declare one service type`() {
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("first", Contribution { it.service(ScopeLevel.App) { Counter() } }, WidgetDataRegistry(), WidgetCommandRegistry(), services))
        val fault = assertIs<ModuleKeysResult.Refused>(
            registerModuleKeys("second", Contribution { it.service(ScopeLevel.App) { Counter() } }, WidgetDataRegistry(), WidgetCommandRegistry(), services),
        ).reason
        assertTrue("first" in fault, fault)
    }

    @Test
    fun `an app key cannot be one an owner provides`() {
        assertIs<ModuleKeysResult.Refused>(registerModuleKeys("test", Contribution { it.owned(greeting) }, WidgetDataRegistry(), WidgetCommandRegistry(), ContributedServices()))
    }

    @Test
    fun `the keys of a module that is gone go missing rather than run its code`() {
        var live = true
        val tree = Tree(module) { live }
        live = false
        assertNull(tree.app.find(greeting), "the factory was not run")
        assertNull(tree.app.findCommand(shout))
        assertFailsWith<IllegalStateException> { tree.host.hostServices.get<Counter>() }
    }

    @Test
    fun `a service's coroutines stop when its level's services close`() {
        lateinit var context: ServiceContext
        val tree = Tree(Contribution { keys -> keys.service(ScopeLevel.Window) { context = it; Counter() } })
        val window = tree.app.child(ScopeLevel.Window)
        assertNotNull(window.services.find<Counter>())
        assertTrue(context.coroutineScope.isActive)
        assertEquals(ScopeLevel.Window, context.level)
        window.close()
        assertFalse(context.coroutineScope.isActive)
    }

    /** Something the host keeps for itself, a session store with its tokens say. */
    private class Secret

    /** Something the host lets every module read. */
    private class Shared

    private fun hostWith(vararg made: () -> Any): MemoryLevelServices = MemoryLevelServices.app(mapOf(ScopeLevel.App to made.toList()))

    @Test
    fun `a jar module reaches the services modules declared and what the host exposed, never the rest of the host`() {
        val seen = HashMap<String, Any?>()
        val reader = Contribution { keys ->
            keys.service(ScopeLevel.App) { context ->
                seen["service.secret"] = context.hostServices.find<Secret>()
                Counter()
            }
            keys.source(greeting) { app ->
                seen["secret"] = app.hostServices.find<Secret>()
                seen["shared"] = app.hostServices.find<Shared>()
                seen["counter"] = app.services.find<Counter>()
                flowSource(MutableStateFlow("read"))
            }
        }
        val sources = WidgetDataRegistry()
        val services = ContributedServices().apply { expose(Shared::class) }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("jar", reader, sources, WidgetCommandRegistry(), services, trusted = false))
        val host = services.host(hostWith({ Secret() }, { Shared() }), Dispatchers.Unconfined)
        val access = HostAccess()
        val app = WidgetScope.app(sources, host, Dispatchers.Unconfined, access = access)

        assertEquals("read", app.current(greeting))
        assertNull(seen["secret"], "the host's own service reached a module it does not trust")
        assertNull(seen["service.secret"], "nor its service maker")
        assertNotNull(seen["shared"], "what the host exposed is there")
        assertNotNull(seen["counter"], "and what modules declared")
        assertNull(app.services.find<Secret>(), "a module widget reads the level as modules see it")
        assertNull(app.hostServices.find<Secret>(), "and a level it holds hands it no host view")
        assertNotNull(access.servicesOf(app)?.find<Secret>(), "the host still has its own")
    }

    @Test
    fun `a level answers its host view only to the access its tree was made with`() {
        val access = HostAccess()
        val app = WidgetScope.app(WidgetDataRegistry(), hostWith({ Secret() }), Dispatchers.Unconfined, access = access)
        val page = app.child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertNotNull(access.servicesOf(page)?.find<Secret>(), "from any level of its tree")
        assertNull(HostAccess().servicesOf(page), "another access is answered nothing")
        val bare = WidgetScope.app(WidgetDataRegistry(), hostWith({ Secret() }), Dispatchers.Unconfined)
        assertNull(access.servicesOf(bare), "nor is a tree made without one")
        val held: LevelContext = page
        assertNull(held as? HostLevelContext, "a level is no factory's context")
    }

    @Test
    fun `two threads asking for a service at once make it once, and both get that one`() {
        val made = AtomicInteger()
        val inMaker = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tree = Tree(Contribution { keys ->
            keys.service(ScopeLevel.App) {
                made.incrementAndGet()
                inMaker.countDown()
                release.await(5, TimeUnit.SECONDS)
                Counter()
            }
        })
        val got = CopyOnWriteArrayList<Counter>()
        val first = Thread { got += tree.app.services.get<Counter>() }.apply { start() }
        assertTrue(inMaker.await(5, TimeUnit.SECONDS), "the first maker never ran")
        val second = Thread { got += tree.app.services.get<Counter>() }.apply { start() }
        // Long enough for a second maker to have started, had the gate let it.
        Thread.sleep(200)
        release.countDown()
        first.join(5_000)
        second.join(5_000)
        assertEquals(1, made.get(), "a second maker ran beside the first")
        assertEquals(2, got.size)
        assertSame(got[0], got[1])
    }

    @Test
    fun `a module compiled into the host is trusted with the host's own services`() {
        var secret: Secret? = null
        val reader = Contribution { keys -> keys.source(greeting) { app -> secret = app.hostServices.find(); flowSource(MutableStateFlow("x")) } }
        val sources = WidgetDataRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("compiled", reader, sources, WidgetCommandRegistry(), services, trusted = true))
        val app = WidgetScope.app(sources, services.host(hostWith({ Secret() }), Dispatchers.Unconfined), Dispatchers.Unconfined)
        assertNotNull(app.current(greeting))
        assertNotNull(secret)
    }

    /** What a trusted module keeps for the host, a session with its tokens say. */
    private class Kept

    @Test
    fun `a service a trusted module keeps for the host reaches the host and the trusted, and no other module`() {
        val sources = WidgetDataRegistry()
        val services = ContributedServices()
        val keeper = Contribution { keys -> keys.hostService(ScopeLevel.App) { Kept() } }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("keeper", keeper, sources, WidgetCommandRegistry(), services, trusted = true))

        val seen = HashMap<String, Any?>()
        val reader = Contribution { keys ->
            keys.service(ScopeLevel.App) { context ->
                seen["maker.host"] = context.hostServices.find<Kept>()
                seen["maker"] = context.services.find<Kept>()
                Counter()
            }
            keys.source(greeting) { app ->
                seen["factory.host"] = app.hostServices.find<Kept>()
                seen["factory"] = app.services.find<Kept>()
                seen["counter"] = app.services.find<Counter>()
                flowSource(MutableStateFlow("read"))
            }
        }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("jar", reader, sources, WidgetCommandRegistry(), services, trusted = false))
        val trusting = SourceKey<Kept?>("test.trusted")
        val friend = Contribution { keys -> keys.source(trusting) { app -> flowSource(MutableStateFlow(app.hostServices.find<Kept>())) } }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("friend", friend, sources, WidgetCommandRegistry(), services, trusted = true))

        val access = HostAccess()
        val app = WidgetScope.app(sources, services.host(MemoryLevelServices.app(), Dispatchers.Unconfined), Dispatchers.Unconfined, access = access)
        val page = app.child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertEquals("read", page.current(greeting))
        listOf("maker.host", "maker", "factory.host", "factory").forEach { assertNull(seen[it], "a module the host does not trust reached it as $it") }
        assertNotNull(seen["counter"], "what modules declared for everyone is there")
        assertNull(page.services.find<Kept>(), "a widget reads the level as modules see it")
        assertNull(page.hostServices.find<Kept>(), "and a level it holds hands it no host view, whatever it opts in to")
        assertNotNull(access.servicesOf(page)?.find<Kept>(), "the host reaches it from below its level")
        assertNotNull(page.current(trusting), "and so does a module it trusts")
    }

    @Test
    fun `a module the host does not trust cannot keep a service for the host, and brings nothing`() {
        val sources = WidgetDataRegistry()
        val services = ContributedServices()
        val sneaky = Contribution { keys ->
            keys.source(greeting) { flowSource(MutableStateFlow("x")) }
            keys.hostService(ScopeLevel.App) { Kept() }
        }
        val refused = assertIs<ModuleKeysResult.Refused>(registerModuleKeys("jar", sneaky, sources, WidgetCommandRegistry(), services, trusted = false))
        assertTrue("trusts" in refused.reason, refused.reason)
        assertNull(sources.declarationOf(greeting.id), "its other keys did not go in either")
        assertNull(services.ownerOf(Kept::class))
    }

    @Test
    fun `a module whose declaration throws an error, not an exception, is refused and handed back, not thrown`() {
        val todo = registerModuleKeys("todo", Contribution { TODO("not written yet") }, WidgetDataRegistry(), WidgetCommandRegistry(), ContributedServices())
        assertIs<NotImplementedError>(assertIs<ModuleKeysResult.Refused>(todo).cause)
        fun deep(n: Int): Int = deep(n + 1) + 1
        val overflow = registerModuleKeys("deep", Contribution { deep(0) }, WidgetDataRegistry(), WidgetCommandRegistry(), ContributedServices())
        assertIs<StackOverflowError>(assertIs<ModuleKeysResult.Refused>(overflow).cause)
    }

    @Test
    fun `a factory that throws an error leaves its key missing instead of crashing the read`() {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val broken = Contribution { keys ->
            keys.source(greeting) { TODO("later") }
            keys.command(shout) { throw LinkageError("a class the jar lacks") }
        }
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("broken", broken, sources, commands, ContributedServices()))
        val app = WidgetScope.app(sources, MemoryLevelServices.app(), Dispatchers.Unconfined, commands)
        assertNull(app.find(greeting))
        assertNull(app.findCommand(shout))
    }

    @Test
    fun `a sample asks whether the module is still there, like its factory`() {
        var live = true
        val tree = Tree(module) { live }
        assertEquals("sample", tree.sources.sampleOf(greeting.id)?.invoke())
        live = false
        assertFailsWith<IllegalStateException> { tree.sources.sampleOf(greeting.id)?.invoke() }
    }

    @Test
    fun `a module that conflicts on a command id registers nothing at all, its sources included`() {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry().apply { registerFactory(shout) { command { } } }
        val services = ContributedServices()
        val refused = assertIs<ModuleKeysResult.Refused>(registerModuleKeys("test", module, sources, commands, services))
        assertTrue(shout.id in refused.reason, refused.reason)
        assertNull(sources.declarationOf(greeting.id), "a source of the module went in")
        assertNull(sources.declarationOf(perWindow.id))
        assertNull(services.ownerOf(Counter::class), "a service of the module went in")
    }

    @Test
    fun `a module loaded again answers with its new code once what the old code made is dropped`() {
        var current: KeyContribution? = Contribution { keys -> keys.source(greeting) { flowSource(MutableStateFlow("first")) } }
        val sources = WidgetDataRegistry()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("test", { current }, sources, WidgetCommandRegistry(), ContributedServices()))
        val app = WidgetScope.app(sources, MemoryLevelServices.app(), Dispatchers.Unconfined)
        val window = app.child(ScopeLevel.Window)
        assertEquals("first", window.current(greeting))

        current = Contribution { keys -> keys.source(greeting) { flowSource(MutableStateFlow("second")) } }
        assertEquals("first", app.current(greeting), "what was made stays until the host drops it")
        app.forgetMade(setOf(greeting.id))
        assertEquals("second", window.current(greeting), "made again from the new code")

        current = Contribution { }
        app.forgetMade(setOf(greeting.id))
        assertNull(app.current(greeting), "a key the new code no longer declares is missing")

        current = null
        app.forgetMade(setOf(greeting.id))
        assertNull(app.current(greeting), "and so is every key of a module that left")
    }

    @Test
    fun `dropping what was made closes it, in every level, and the next read makes it again`() {
        val made = CopyOnWriteArrayList<Counter>()
        val window = SourceKey<Counter>("test.counter", ScopeLevel.Window)
        val sources = WidgetDataRegistry().apply {
            registerFactory(window) { _ -> object : WidgetDataSource<Counter>, AutoCloseable {
                val counter = Counter().also { made += it }
                override val state = MutableStateFlow(counter)
                override fun close() = counter.close()
            } }
        }
        val app = WidgetScope.app(sources, MemoryLevelServices.app(), Dispatchers.Unconfined)
        val first = app.child(ScopeLevel.Window)
        val firstCounter = assertNotNull(first.current(window))
        app.forgetMade(setOf(window.id))
        assertTrue(firstCounter.closed)
        assertTrue(first.current(window) !== firstCounter)
        assertEquals(2, made.size)
    }
}
