// The test plays the host: it makes the levels and registers the module's keys the way the launcher does.
@file:OptIn(HostApi::class)

package hivens.look.colour.nx.controls

import hivens.themes.Themes
import hivens.themes.api.ThemesKeys
import hivens.look.preview.NeutralTheme
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The picker's pending choice: one per page, starting at the theme in use when the page first reads it,
 * and shown in the palette on a sample that stands for the theme in use, whatever that is there.
 */
class ThemeSelectionTest {

    private val active = MutableStateFlow(Themes.default)

    private val data = WidgetDataRegistry().apply {
        register(ThemesKeys.Active, flowSource(active))
    }

    init {
        registerModuleKeys(ColourNxControls.ID, ControlsKeys, data, WidgetCommandRegistry(), ContributedServices(), trusted = true)
    }

    private val app = WidgetScope.app(data, NoServices, Dispatchers.Unconfined)

    private fun pageUnder(app: WidgetScope): WidgetScope = app.child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)

    @Test
    fun `the palette's sample stands for the theme in use, so it holds none of its own`() {
        val sample = data.sampleOf(ThemeSelectionSource.id)?.invoke() as ThemeSelection
        assertNull(sample.theme)
        assertEquals(NeutralTheme, sample.or(NeutralTheme))
    }

    @Test
    fun `a page's choice starts at the theme in use and stays the page's own`() {
        val page = pageUnder(app)
        val selection = page.find(ThemeSelectionSource)!!.state.value
        assertEquals(Themes.default, selection.theme)

        val other = Themes.all.first { it.id != Themes.default.id }
        selection.theme = other
        active.value = Themes.all.last()
        assertSame(selection, page.find(ThemeSelectionSource)!!.state.value, "the page holds one choice for its life")
        assertEquals(other, selection.theme, "a change of the theme in use does not move a choice already made")

        val another = pageUnder(app).find(ThemeSelectionSource)!!.state.value
        assertNotSame(selection, another)
        assertEquals(Themes.all.last(), another.theme, "a new page starts at the theme in use then")
    }

    /** A level with no services at all: the choice is made of keys alone. */
    private object NoServices : LevelServicesHost {
        override val services: LevelServices = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null

            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException("no services here")
        }

        override fun child(level: ScopeLevel): LevelServicesHost = this

        override fun close() = Unit
    }
}
