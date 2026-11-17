// The test plays the host: it makes the levels and registers the module's keys the way the launcher does.
@file:OptIn(HostApi::class)

package hivens.look.colour.nx.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The controls steer the nx colour provider and nothing else: each draws, and offers its puppet
 * handles, only while the look's state says nx draws the colour, and is absent, at no size and with
 * no handle, where another family draws it, where the base answers hold, and where no look manager
 * says what draws it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ControlsHideTest {

    /** Each control by name, and a puppet handle it offers while it draws, where it offers one. */
    private val controls: List<Triple<String, String?, @Composable () -> Unit>> = listOf(
        Triple("day and night", "settings.darkTheme", { DayNightWidget() }),
        Triple("dark only", null, { DarkOnlyWidget() }),
        Triple("mode", null, { ThemeModeWidget() }),
        Triple("picker link", "settings.openThemePicker", { ThemePickerLinkWidget() }),
        Triple("picker header", "themePicker.apply", { ThemePickerHeaderWidget() }),
        Triple("picker grid", "themePicker.select.${Themes.default.id}", { ThemePickerGridWidget() }),
        Triple("picker preview", null, { ThemePickerPreviewWidget() }),
    )

    /** Draws [control] with the look's state [state], none where null, and says the size it took and whether [handle] was offered. */
    private fun draw(state: LookState?, handle: String?, control: @Composable () -> Unit): Pair<IntSize, Boolean> {
        // A theme with no light scheme in light mode, so the dark only note has something to say.
        val darkOnly = Themes.all.first { !it.hasLight }
        val data = WidgetDataRegistry().apply {
            register(ThemesKeys.Offered, flowSource(MutableStateFlow(Themes.all)))
            register(ThemesKeys.Active, flowSource(MutableStateFlow(darkOnly)))
            register(ThemesKeys.Dark, flowSource(MutableStateFlow(false)))
            register(ThemesKeys.Mode, flowSource(MutableStateFlow(ThemeMode.Manual)))
            register(ThemesKeys.SystemSchemeAvailable, flowSource(MutableStateFlow(true)))
            if (state != null) register(LookKeys.State, flowSource(MutableStateFlow(state)))
        }
        val commands = WidgetCommandRegistry().apply {
            register(ThemesKeys.Choose, command { _: String -> })
            register(ThemesKeys.SetDark, command { _: Boolean -> })
            register(ThemesKeys.SetMode, command { _: ThemeMode -> })
        }
        registerModuleKeys(ColourNxControls.ID, ControlsKeys, data, commands, ContributedServices(), trusted = true)
        val page = WidgetScope.app(data, NoServices, Dispatchers.Unconfined, commands)
            .child(ScopeLevel.Window).child(ScopeLevel.Composition).child(ScopeLevel.Page)
        var size = IntSize.Zero
        var offered = false
        val scene = ImageComposeScene(900, 900, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                CompositionLocalProvider(
                    LocalWidgetDataRegistry provides data,
                    LocalWidgetCommandRegistry provides commands,
                    LocalScope provides page,
                ) {
                    Box(Modifier.onSizeChanged { size = it }) { control() }
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
    fun `each control draws while nx draws the colour`() {
        controls.forEach { (name, handle, control) ->
            val (size, offered) = draw(colourBy(ThemesApi.COLOUR_FAMILY), handle, control)
            assertNotEquals(0, size.height, "$name drew nothing while nx draws the colour")
            if (handle != null) assertTrue(offered, "$name did not offer $handle")
        }
    }

    @Test
    fun `each control is absent where another family, the base answers or nothing says what draws the colour`() {
        listOf(colourBy("material"), colourBy(null), null).forEach { state ->
            controls.forEach { (name, handle, control) ->
                val (size, offered) = draw(state, handle, control)
                assertEquals(IntSize.Zero, size, "$name drew under $state")
                assertEquals(false, offered, "$name offered $handle under $state")
            }
        }
    }

    @Test
    fun `the module brings exactly these widgets, the picker's under the kinds saved layouts name`() {
        assertEquals(
            setOf("theme.daynight", "theme.darkonly", "theme.mode", "theme.picker.link", "theme.picker.header", "theme.picker.grid", "theme.picker.preview"),
            ColourNxControls.widgets.all().keys.map { it.value }.toSet(),
        )
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
}
