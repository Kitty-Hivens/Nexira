package hivens.ui.widgets.themepicker

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.themes.Themes
import hivens.themes.api.ThemesKeys
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.settle
import hivens.ui.theme.NxThemed
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.Commands
import hivens.ui.widgets.KoinLevelServicesHost
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
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Apply on the theme picker puts the choice in use and then leaves, in that order,
 * and with nothing to put it in use it does nothing at all rather than leave a
 * choice behind that did not land.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ThemePickerApplyTest {

    /** Draws the picker in a page, with Apply answered only when [answerApply], presses Apply, and says what it fired. */
    private fun pressApply(answerApply: Boolean): List<String> {
        val fired = ArrayList<String>()
        // The themes on offer, the one in use and the choice are the themes module's app
        // keys, answered here as the app's own values and command.
        val data = WidgetDataRegistry().apply {
            register(ThemesKeys.Offered, flowSource(MutableStateFlow(Themes.all)))
            register(ThemesKeys.Active, flowSource(MutableStateFlow(Themes.default)))
            declare(ThemeSelectionSource)
        }
        val commands = WidgetCommandRegistry().apply {
            if (answerApply) register(ThemesKeys.Choose, command { id: String -> fired += "apply $id" })
            declare(Commands.Back)
        }
        val app: WidgetScope = koinApplication {
            modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) } })
        }.koin.get()
        onEventThread {
            val scene = ImageComposeScene(900, 700, density = Density(1f)) {
                ShippedLanguageProvider("en") {
                    NxThemed(Themes.default, dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                            LocalLayoutGraph provides LayoutGraph.EMPTY,
                            LocalWidgetRegistry provides GeneratedWidgetRegistry,
                            LocalSurfaceFamilies provides SurfaceFamilies(),
                        ) {
                            ChildLevel(ScopeLevel.Composition) {
                                val back = rememberLatestCommand<Unit> { fired += "back" }
                                ProvideScopedCommand(Commands.Back, back) {
                                    ChildLevel(ScopeLevel.Page) { ThemePickerSurface() }
                                }
                            }
                        }
                    }
                }
            }
            try {
                scene.settle(4)
                PuppetRegistry.click("themePicker.apply").getOrThrow()
            } finally {
                scene.close()
            }
        }
        return fired
    }

    @Test
    fun `apply puts the choice in use and then leaves`() {
        assertEquals(listOf("apply ${Themes.default.id}", "back"), pressApply(answerApply = true))
    }

    @Test
    fun `with nothing to apply it, apply does not leave`() {
        assertEquals(emptyList(), pressApply(answerApply = false))
    }
}
