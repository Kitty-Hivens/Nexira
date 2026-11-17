package hivens.ui.widgets.themepicker

import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourApi
import hivens.look.colour.nx.controls.ColourNxControls
import hivens.look.manager.api.LookAxisState
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookSource
import hivens.look.manager.api.LookState
import hivens.themes.api.ThemesApi
import hivens.widget.api.CompositeWidgetRegistry
import hivens.widget.api.ContributedServices
import hivens.widget.api.KeyContribution
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.generated.GeneratedWidgetRegistry
import kotlinx.coroutines.flow.MutableStateFlow

/** The look's state with [family] drawing the colour axis, nx's by default. */
internal fun lookDrawingColour(family: String? = ThemesApi.COLOUR_FAMILY): LookState = LookState(
    LookChoice.DEFAULT,
    listOf(LookAxisState(Colour.id, ColourApi.VERSION, family, if (family == null) LookSource.Base else LookSource.Pack)),
    emptyList(),
    emptyList(),
)

/** The launcher's own widgets and the colour provider's controls, in the order the launcher draws them. */
internal val launcherAndControls: WidgetRegistry = CompositeWidgetRegistry(listOf(GeneratedWidgetRegistry, ColourNxControls.widgets))

/**
 * The controls' keys and the look's state with [family] drawing the colour, put into [data] and
 * [commands] the way the launcher brings them in.
 */
internal fun bringInControls(data: WidgetDataRegistry, commands: WidgetCommandRegistry, family: String? = ThemesApi.COLOUR_FAMILY) {
    data.register(LookKeys.State, flowSource(MutableStateFlow(lookDrawingColour(family))))
    ColourNxControls.contributions.filterIsInstance<KeyContribution>().forEach { keys ->
        registerModuleKeys(ColourNxControls.ID, keys, data, commands, ContributedServices(), trusted = true)
    }
}
