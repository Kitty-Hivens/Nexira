package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A module's settings domain declared through its keys: read and changed through them, and one id per launcher. */
class SettingsDomainKeysTest {

    @Serializable
    data class Prefs(val volume: Int = 5)

    private object PrefsDomain : SettingsDomain<Prefs>("test.prefs", Prefs.serializer()) {
        override fun defaults() = Prefs()
    }

    private val read = SourceKey<Prefs>("test.prefs")
    private val change = CommandKey<SettingsChange<Prefs>>("test.prefs.update")

    /** What the host keeps, in memory. */
    private class Stores : SettingsStores {
        val opened = HashMap<String, Store<*>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> of(domain: SettingsDomain<T>): SettingsStore<T> =
            opened.getOrPut(domain.id) { Store(domain.defaults()) } as SettingsStore<T>
    }

    private class Store<T : Any>(initial: T) : SettingsStore<T> {
        private val flow = MutableStateFlow(initial)
        override val value: StateFlow<T> = flow
        override val readOnly = false
        override fun update(change: SettingsChange<T>) {
            synchronized(this) { flow.value = change.apply(flow.value) }
        }
    }

    private class Contribution(private val body: (ModuleKeys) -> Unit) : KeyContribution {
        override val id = "test.keys"
        override val label = "Test keys"
        override fun declare(keys: ModuleKeys) = body(keys)
    }

    private val module = Contribution { keys -> keys.settings(PrefsDomain, read, change) }

    @Test
    fun `the value is read through the module's source and changed through its command`() {
        val stores = Stores()
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("test", module, sources, commands, services))
        val host = services.host(MemoryLevelServices.app(mapOf(ScopeLevel.App to listOf({ stores }))), Dispatchers.Unconfined)
        val app = WidgetScope.app(sources, host, Dispatchers.Unconfined, commands)

        assertEquals(Prefs(5), app.current(read))
        assertEquals(Prefs(), sources.sampleOf(read.id)?.invoke(), "the defaults are the sample")
        assertNotNull(app.findCommand(change)).run { it.copy(volume = it.volume + 1) }
        assertEquals(Prefs(6), app.current(read))
        assertEquals("test", services.ownerOfDomain(PrefsDomain.id))
    }

    @Test
    fun `a domain id another module declared refuses the second module whole`() {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("first", module, sources, commands, services))
        val other = Contribution { keys ->
            keys.settings(PrefsDomain, SourceKey("other.prefs"), CommandKey("other.prefs.update"))
            keys.source(SourceKey<Int>("other.thing")) { flowSource(MutableStateFlow(1)) }
        }
        val refused = assertIs<ModuleKeysResult.Refused>(registerModuleKeys("second", other, sources, commands, services))
        assertTrue("test.prefs" in refused.reason, refused.reason)
        assertNull(sources.declarationOf("other.thing"), "none of its keys came in")
    }

    @Test
    fun `a domain read through keys below the app, or named badly, is refused`() {
        val window = Contribution { keys -> keys.settings(PrefsDomain, SourceKey("w.prefs", ScopeLevel.Window), change) }
        val badId = object : SettingsDomain<Prefs>("Has Spaces", Prefs.serializer()) {
            override fun defaults() = Prefs()
        }
        val named = Contribution { keys -> keys.settings(badId, read, change) }
        listOf(window, named).forEach { contribution ->
            val result = registerModuleKeys("test", contribution, WidgetDataRegistry(), WidgetCommandRegistry(), ContributedServices())
            assertIs<ModuleKeysResult.Refused>(result)
        }
    }

    private object OtherDomain : SettingsDomain<Prefs>("test.other", Prefs.serializer()) {
        override fun defaults() = Prefs(volume = 1)
    }

    /** A second module whose own key and own service each reach for [target], which is the first module's. */
    private fun intruder(target: SettingsDomain<Prefs>) = Contribution { keys ->
        keys.settings(OtherDomain, SourceKey("other.prefs"), CommandKey("other.prefs.update"))
        keys.source(SourceKey<Prefs>("other.peek")) { app -> flowSource(app.services.get<SettingsStores>().of(target).value) }
        keys.service(ScopeLevel.App) { context -> Peeker(context.services.get<SettingsStores>().of(target)) }
        keys.source(SourceKey<Int>("other.peek.service")) { app -> flowSource(MutableStateFlow(app.services.get<Peeker>().store.value.value.volume)) }
    }

    private class Peeker(val store: SettingsStore<Prefs>)

    @Test
    fun `a module gets the stores of its own domains and no other's, from a key or from a service`() {
        val stores = Stores()
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("owner", module, sources, commands, services))
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("intruder", intruder(PrefsDomain), sources, commands, services, trusted = true))
        val host = services.host(MemoryLevelServices.app(mapOf(ScopeLevel.App to listOf({ stores }))), Dispatchers.Unconfined)
        val app = WidgetScope.app(sources, host, Dispatchers.Unconfined, commands)

        assertNull(app.find(SourceKey<Prefs>("other.peek")), "a key of another module is refused the owner's store")
        assertNull(app.find(SourceKey<Int>("other.peek.service")), "and so is a service of it, trusted or not")
        assertEquals(Prefs(volume = 1), app.current(SourceKey<Prefs>("other.prefs")), "its own domain it still reads")
        assertEquals(Prefs(5), app.current(read), "and the owner's is untouched")
        assertNull(app.services.find(SettingsStores::class), "a widget holding the level finds no stores at all")
    }

    @Test
    fun `a module loaded again that declares another module's domain answers none of its keys`() {
        val stores = Stores()
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("owner", module, sources, commands, services))
        val first = Contribution { keys -> keys.settings(OtherDomain, SourceKey("other.prefs"), CommandKey("other.prefs.update")) }
        val again = Contribution { keys ->
            keys.settings(OtherDomain, SourceKey("other.prefs"), CommandKey("other.prefs.update"))
            keys.settings(PrefsDomain, SourceKey("other.taken"), CommandKey("other.taken.update"))
        }
        var current: KeyContribution = first
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys("reloaded", { current }, sources, commands, services))
        val host = services.host(MemoryLevelServices.app(mapOf(ScopeLevel.App to listOf({ stores }))), Dispatchers.Unconfined)
        val app = WidgetScope.app(sources, host, Dispatchers.Unconfined, commands)

        current = again
        assertNull(app.find(SourceKey<Prefs>("other.prefs")), "the reload is refused, so its keys are missing")
        assertNotNull(app.findCommand(change)).run { it.copy(volume = 9) }
        assertEquals(Prefs(9), app.current(read), "the owner keeps its domain")
        assertEquals("owner", services.ownerOfDomain(PrefsDomain.id))
    }
}
