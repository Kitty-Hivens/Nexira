// The test plays the host: it makes the levels and registers the module's keys the way the launcher does.
@file:OptIn(HostApi::class)

package hivens.look.colour.nx.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourApi
import hivens.look.manager.api.LookAxisState
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookSource
import hivens.look.manager.api.LookState
import hivens.themes.Themes
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.theme.NxThemed
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The controls steer the nx colour provider and nothing else: each is shown under the module's own
 * source saying whether nx draws the colour, which follows the look's state. A slot places each, with
 * its puppet handles, only while that holds, and leaves it out, at no size and with no handle, where
 * another family draws the colour, where the base answers hold, and where no look manager says what
 * draws it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ControlsHideTest {

    /** Each control by name and kind, and a puppet handle it offers while it draws, where it offers one. */
    private val controls: List<Triple<String, String, String?>> = listOf(
        Triple("day and night", "theme.daynight", "settings.darkTheme"),
        Triple("dark only", "theme.darkonly", null),
        Triple("mode", "theme.mode", null),
        Triple("picker link", "theme.picker.link", "settings.openThemePicker"),
        Triple("picker header", "theme.picker.header", "themePicker.apply"),
        Triple("picker grid", "theme.picker.grid", "themePicker.select.${Themes.default.id}"),
        Triple("picker preview", "theme.picker.preview", null),
    )

    /** The registries a host fills, with the look's state [state] answered as given, none where null. */
    private fun registries(state: MutableStateFlow<LookState>?): Pair<WidgetDataRegistry, WidgetCommandRegistry> {
        // A theme with no light scheme in light mode, so the dark only note has something to say.
        val darkOnly = Themes.all.first { !it.hasLight }
        val data = WidgetDataRegistry().apply {
            register(ThemesKeys.Offered, flowSource(MutableStateFlow(Themes.all)))
            register(ThemesKeys.Active, flowSource(MutableStateFlow(darkOnly)))
            register(ThemesKeys.Dark, flowSource(MutableStateFlow(false)))
            register(ThemesKeys.Mode, flowSource(MutableStateFlow(ThemeMode.Manual)))
            register(ThemesKeys.SystemSchemeAvailable, flowSource(MutableStateFlow(true)))
            if (state != null) register(LookKeys.State, flowSource(state))
        }
        val commands = WidgetCommandRegistry().apply {
            register(ThemesKeys.Choose, command { _: String -> })
            register(ThemesKeys.SetDark, command { _: Boolean -> })
            register(ThemesKeys.SetMode, command { _: ThemeMode -> })
        }
        registerModuleKeys(ColourNxControls.ID, ControlsKeys, data, commands, ContributedServices(), trusted = true)
        return data to commands
    }

    /** Places the control of [kind] in a slot under the look's state [state], and says the size the slot took and whether [handle] was offered. */
    private fun draw(state: LookState?, kind: String, handle: String?): Pair<IntSize, Boolean> {
        val (data, commands) = registries(state?.let { MutableStateFlow(it) })
        val page = WidgetScope.app(data, NoServices, Dispatchers.Unconfined, commands)
            .child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)
        val graph = LayoutGraph(
            surfaces = mapOf(SURFACE to SurfaceLayout(slots = mapOf(SLOT to SlotContent(widgets = listOf(WidgetInstance(WidgetKind(kind), "probe")))))),
        )
        var size = IntSize.Zero
        var offered = false
        val scene = ImageComposeScene(900, 900, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                CompositionLocalProvider(
                    LocalWidgetDataRegistry provides data,
                    LocalWidgetCommandRegistry provides commands,
                    LocalScope provides page,
                    LocalLayoutGraph provides graph,
                    LocalWidgetRegistry provides ColourNxControls.widgets,
                ) {
                    Box(Modifier.onSizeChanged { size = it }) { SlotRenderer(SURFACE, SLOT) }
                }
            }
        }
        try {
            repeat(6) { scene.render(it * 16_000_000L).close() }
            offered = handle != null && PuppetRegistry.snapshot().elements.any { it.id == handle }
        } finally {
            scene.close()
        }
        return size to offered
    }

    private fun colourBy(family: String?) = LookState(
        LookChoice.DEFAULT,
        listOf(LookAxisState(Colour.id, ColourApi.VERSION, family, if (family == null) LookSource.Base else LookSource.Pack)),
        emptyList(),
        emptyList(),
    )

    @Test
    fun `every control is shown under whether nx draws the colour`() {
        val all = ColourNxControls.widgets.all().values
        assertTrue(all.isNotEmpty())
        all.forEach { descriptor ->
            assertEquals(NX_DRAWING_ID, descriptor.shownWhen, "${descriptor.kind.value} names no condition, so it would draw under any provider")
        }
    }

    @Test
    fun `the source follows the look's state, and is false where no look manager answers`() {
        val state = MutableStateFlow(colourBy(ThemesApi.COLOUR_FAMILY))
        val (data, commands) = registries(state)
        val app = WidgetScope.app(data, NoServices, Dispatchers.Unconfined, commands)
        assertEquals(true, app.current(NxDrawing), "nx draws the colour")
        state.value = colourBy("material")
        assertEquals(false, app.current(NxDrawing), "another family draws it")
        state.value = colourBy(null)
        assertEquals(false, app.current(NxDrawing), "the base answers hold")
        state.value = colourBy(ThemesApi.COLOUR_FAMILY)
        assertEquals(true, app.current(NxDrawing), "nx draws it again")

        val (bare, bareCommands) = registries(null)
        assertEquals(false, WidgetScope.app(bare, NoServices, Dispatchers.Unconfined, bareCommands).current(NxDrawing))
    }

    @Test
    fun `each control is placed while nx draws the colour`() {
        controls.forEach { (name, kind, handle) ->
            val (size, offered) = draw(colourBy(ThemesApi.COLOUR_FAMILY), kind, handle)
            assertNotEquals(0, size.height, "$name drew nothing while nx draws the colour")
            if (handle != null) assertTrue(offered, "$name did not offer $handle")
        }
    }

    @Test
    fun `each control is left out where another family, the base answers or nothing says what draws the colour`() {
        listOf(colourBy("material"), colourBy(null), null).forEach { state ->
            controls.forEach { (name, kind, handle) ->
                val (size, offered) = draw(state, kind, handle)
                assertEquals(IntSize.Zero, size, "$name took room under $state")
                assertEquals(false, offered, "$name offered $handle under $state")
            }
        }
    }

    @Test
    fun `the module brings exactly these widgets, the picker's under the kinds saved layouts name`() {
        assertEquals(controls.map { it.second }.toSet(), ColourNxControls.widgets.all().keys.map { it.value }.toSet())
    }

    /** A level with no services: the controls read keys alone. */
    private object NoServices : LevelServicesHost {
        override val services: LevelServices = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null

            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException("no services here")
        }

        override fun child(level: ScopeLevel): LevelServicesHost = this

        override fun close() = Unit
    }

    private companion object {
        val SURFACE = SurfaceId("controls.test")
        val SLOT = SlotId("main")
    }
}
