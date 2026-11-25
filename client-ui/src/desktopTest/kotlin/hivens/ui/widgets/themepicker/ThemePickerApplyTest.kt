package hivens.ui.widgets.themepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.themes.Themes
import hivens.themes.api.ThemesKeys
import hivens.ui.FRAME_NANOS
import hivens.ui.ScreenRouter
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.onEventThread
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.theme.NxThemed
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.CompositionKeys
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.ProvideScopedCommand
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.rememberLatestCommand
import hivens.widget.model.DefaultLayout
import hivens.widget.model.History
import hivens.widget.model.ScopeLevel
import hivens.widget.model.Show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Apply on the theme picker puts the choice in use and then leaves, in that order, and with nothing
 * to put it in use it does nothing at all rather than leave a choice behind that did not land. The
 * choice not yet applied is the page's, so it is still there after going back and forward again, and
 * a picker opened afresh starts at the theme in use.
 *
 * The picker is drawn the way the launcher draws it: the host's frame, the bundled layout's slots, and
 * the colour provider's controls in them.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ThemePickerApplyTest {

    private val other = Themes.all.first { it.id != Themes.default.id }

    /** The picker's world, with Apply answered only when [answerApply], and what it fired. */
    private class World(answerApply: Boolean) {
        val fired = ArrayList<String>()
        // The themes on offer, the one in use and the choice are the themes module's app
        // keys, answered here as the app's own values and command.
        val data = WidgetDataRegistry().apply {
            register(ThemesKeys.Offered, flowSource(MutableStateFlow(Themes.all)))
            register(ThemesKeys.Active, flowSource(MutableStateFlow(Themes.default)))
            register(ThemesKeys.Dark, flowSource(MutableStateFlow(true)))
        }
        val commands = WidgetCommandRegistry().apply {
            if (answerApply) register(ThemesKeys.Choose, command { id: String -> fired += "apply $id" })
            declare(CompositionKeys.Back)
        }
        val app: WidgetScope

        init {
            bringInControls(data, commands)
            app = koinApplication {
                modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) } })
            }.koin.get()
        }

        @Composable
        fun Draw(content: @Composable () -> Unit) {
            ShippedLanguageProvider("en") {
                NxThemed(Themes.default, dark = true) {
                    CompositionLocalProvider(
                        LocalWidgetDataRegistry provides data,
                        LocalWidgetCommandRegistry provides commands,
                        LocalScope provides app,
                        LocalLayoutGraph provides DefaultLayout.load(),
                        LocalWidgetRegistry provides launcherAndControls,
                        LocalSurfaceFamilies provides SurfaceFamilies(),
                    ) {
                        ChildLevel(ScopeLevel.Composition) {
                            val back = rememberLatestCommand<Unit> { fired += "back" }
                            ProvideScopedCommand(CompositionKeys.Back, back) { content() }
                        }
                    }
                }
            }
        }
    }

    /** Draws the picker in a page, presses Apply, and says what it fired. */
    private fun pressApply(answerApply: Boolean): List<String> {
        val world = World(answerApply)
        onEventThread {
            val scene = ImageComposeScene(900, 700, density = Density(1f)) {
                world.Draw { ChildLevel(ScopeLevel.Page) { ThemePickerSurface() } }
            }
            try {
                repeat(8) { scene.render(it * FRAME_NANOS).close() }
                PuppetRegistry.click("themePicker.apply").getOrThrow()
            } finally {
                scene.close()
            }
        }
        return world.fired
    }

    @Test
    fun `apply puts the choice in use and then leaves`() {
        assertEquals(listOf("apply ${Themes.default.id}", "back"), pressApply(answerApply = true))
    }

    @Test
    fun `with nothing to apply it, apply does not leave`() {
        assertEquals(emptyList(), pressApply(answerApply = false))
    }

    /**
     * Picks [other] on the picker, goes [away] from it and comes back by [returning], and says
     * what Apply fired then.
     */
    private fun pickLeaveAndReturn(away: History.() -> History, returning: History.() -> History): List<String> {
        val world = World(answerApply = true)
        var history by mutableStateOf(History(LauncherConfigs.Home).show(Show(LauncherConfigs.Themes)))
        onEventThread {
            val scene = ImageComposeScene(900, 700, density = Density(1f)) {
                world.Draw {
                    ScreenRouter(history) { if (it.config == LauncherConfigs.Themes) ThemePickerSurface() }
                }
            }
            var t = 0L
            fun frames(count: Int) = repeat(count) {
                scene.render(t).close()
                t += FRAME_NANOS
            }
            try {
                frames(8)
                PuppetRegistry.click("themePicker.select.${other.id}").getOrThrow()
                frames(4)
                history = history.away()
                frames(90)
                history = history.returning()
                frames(90)
                PuppetRegistry.click("themePicker.apply").getOrThrow()
            } finally {
                scene.close()
            }
        }
        return world.fired
    }

    @Test
    fun `a choice not yet applied is still there after going back and forward again`() {
        // On to another screen and back: the picker never left the history, so its page did not.
        assertEquals(
            listOf("apply ${other.id}", "back"),
            pickLeaveAndReturn(away = { show(Show(LauncherConfigs.About)) }, returning = { back() }),
        )
    }

    @Test
    fun `a picker opened afresh starts at the theme in use`() {
        // The picker left the history, as a switch of tab leaves it, and was opened again: a new page.
        assertEquals(
            listOf("apply ${Themes.default.id}", "back"),
            pickLeaveAndReturn(away = { show(LauncherConfigs.section(LauncherConfigs.About)) }, returning = { show(Show(LauncherConfigs.Themes)) }),
        )
    }
}
