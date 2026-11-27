package hivens.history

import hivens.history.api.HistoryKeys
import hivens.pages.api.PageValue
import hivens.pages.api.PagesKeys
import hivens.pages.api.ScreenId
import hivens.pages.api.ScreenView
import hivens.pages.api.Show
import hivens.widget.api.CompositionKeys
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.InterfaceKeys
import hivens.widget.api.InterfaceRestarts
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.CompositionName
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.serializer
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The history module's keys as a composition reads them: kept by the composition's name across its levels, moved by
 * its commands, holding to the screens the pages module gives it, and stepped back after the interface crashed.
 */
@OptIn(HostApi::class)
class HistoryKeysTest {

    private val home = ScreenId("home")
    private val library = ScreenId("library")
    private val pack = ScreenId("pack")

    private val restarts = MutableStateFlow(InterfaceRestarts())
    private val packValue = PageValue("pack.page", String.serializer())
    private val packs = MutableStateFlow(setOf(packValue.json("a"), packValue.json("b")))
    private fun screens(ids: List<ScreenId>) = ids.map { ScreenView(it, it.value, owner = "test") }
    private val screens = MutableStateFlow(screens(listOf(home, library, pack)))

    private val app: WidgetScope = run {
        val sources = WidgetDataRegistry().apply {
            register(InterfaceKeys.Restarts, flowSource(restarts))
            // As the module of the value answers which of its values are still there.
            register(packValue.present, flowSource(packs))
        }
        val commands = WidgetCommandRegistry()
        val services = ContributedServices()
        assertIs<ModuleKeysResult.Registered>(registerModuleKeys(HistoryModule.id, HistoryKeysContribution, sources, commands, services, trusted = true))
        WidgetScope.app(sources, services.host(NoServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands)
    }

    /** A level of the composition "main", as whoever draws it hands it its name and the pages module its screens. */
    private fun composition(): WidgetScope = app.child(ScopeLevel.Window).child(ScopeLevel.Composition).apply {
        provide(CompositionKeys.Name, CompositionName("main"))
        provide(PagesKeys.Start, home)
        provideSource(PagesKeys.Screens, flowSource(screens))
    }

    private fun WidgetScope.shown(): ScreenId? = current(HistoryKeys.History)?.current?.screen

    @Test
    fun `a page shown moves the history, and back and forward walk it`() {
        val main = composition()
        assertEquals(home, main.shown())
        main.findCommand(PagesKeys.Show)!!.run(Show(library))
        assertEquals(library, main.shown())
        main.findCommand(HistoryKeys.Back)!!.run(Unit)
        assertEquals(home, main.shown())
        main.findCommand(HistoryKeys.Forward)!!.run(Unit)
        assertEquals(library, main.shown())
    }

    @Test
    fun `a screen the composition does not have is not shown`() {
        val main = composition()
        main.findCommand(PagesKeys.Show)!!.run(Show(ScreenId("nowhere")))
        assertEquals(home, main.shown())
    }

    @Test
    fun `the composition drawn again on a new level goes on from where it stood`() {
        val first = composition()
        first.findCommand(PagesKeys.Show)!!.run(Show(pack))
        first.close()
        assertEquals(pack, composition().shown())
    }

    @Test
    fun `a screen gone leaves the history`() {
        val main = composition()
        main.findCommand(PagesKeys.Show)!!.run(Show(library))
        main.findCommand(PagesKeys.Show)!!.run(Show(pack))
        screens.value = screens(listOf(home, library))
        assertEquals(library, main.shown())
        main.findCommand(HistoryKeys.Forward)!!.run(Unit)
        assertEquals(library, main.shown(), "nor is it ahead")
    }

    @Test
    fun `a page opened with a value its module says is gone leaves the history, and one no module answers for stays`() {
        val main = composition()
        main.findCommand(PagesKeys.Show)!!.run(Show(pack, mapOf(packValue.of("a"))))
        main.findCommand(PagesKeys.Show)!!.run(Show(pack, mapOf(packValue.of("b"))))
        main.findCommand(PagesKeys.Show)!!.run(Show(library, mapOf("nobody.answers" to packValue.json("x"))))
        main.findCommand(HistoryKeys.Back)!!.run(Unit)
        packs.value = setOf(packValue.json("a"))
        assertEquals(listOf(home, pack), main.current(HistoryKeys.History)!!.entries.map { it.screen })
        assertEquals(packValue.json("a"), main.current(HistoryKeys.History)!!.current.opened["pack.page"], "the deleted pack's page is gone")
        main.findCommand(HistoryKeys.Forward)!!.run(Unit)
        assertEquals(library, main.shown(), "a value nobody answers for is never gone")
    }

    @Test
    fun `after the interface crashed the composition drawn again stands one step back`() {
        val first = composition()
        first.findCommand(PagesKeys.Show)!!.run(Show(library))
        first.findCommand(PagesKeys.Show)!!.run(Show(pack))
        first.close()
        restarts.value = InterfaceRestarts(count = 1, again = false)
        assertEquals(library, composition().shown())
    }
}

/** A host with no services of its own, under the module's. */
private object NoServices : LevelServicesHost {
    override val services: LevelServices = object : LevelServices {
        override fun <T : Any> find(type: KClass<T>): T? = null
        override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException("no ${type.qualifiedName} here")
    }

    override fun child(level: ScopeLevel): LevelServicesHost = this

    override fun close() = Unit
}
