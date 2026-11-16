package hivens.ui.widgets.themepicker

import hivens.themes.Themes
import hivens.themes.ThemesKeysContribution
import hivens.themes.api.ThemesApi
import hivens.ui.background.BackgroundSettings
import hivens.ui.theme.NeutralTheme
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.widget.api.ContributedServices
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the picker's widgets are shown on in the palette: the sample of the theme in use, the themes module's where it is brought in. */
class ThemePickerSampleTest {

    private fun registry() = uiDataRegistry(
        activity      = MutableStateFlow(emptyList()),
        notifications = MutableStateFlow(emptyList()),
        doNotDisturb  = MutableStateFlow(false),
        sslBypass     = flowSource(MutableStateFlow(false)),
        background    = MutableStateFlow(BackgroundSettings()),
    )

    private fun sampled(registry: WidgetDataRegistry) =
        (registry.sampleOf(ThemeSelectionSource.id)?.invoke() as ThemeSelection).theme

    @Test
    fun `with the themes module brought in, the picker is shown on its sample of the theme in use`() {
        val registry = registry()
        registerModuleKeys(ThemesApi.ID, ThemesKeysContribution, registry, uiCommandRegistry({}, {}, {}), ContributedServices(), trusted = true)
        assertEquals(Themes.default, sampled(registry))
    }

    @Test
    fun `without it, on the neutral theme`() {
        assertEquals(NeutralTheme, sampled(registry()))
    }
}
