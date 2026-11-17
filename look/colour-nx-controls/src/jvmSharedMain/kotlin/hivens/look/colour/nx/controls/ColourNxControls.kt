package hivens.look.colour.nx.controls

import hivens.themes.api.ThemesKeys
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.flowSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The nx colour provider's controls as the application carries them: compiled in, its widgets drawn
 * by kind wherever a layout places them, and its one key brought in by a document that names [ID].
 *
 * It steers the themes module through that module's own keys, and asks the look's state only whether
 * nx draws the colour. It owns no setting and decides nothing of how anything is drawn.
 */
object ColourNxControls : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.colour.nx.controls"

    override val id: String = ID
    override val name: String = "controls.name"
    override val strings: ModuleStrings = ModuleStrings.resources(ColourNxControls::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(ControlsKeys)
    override val widgets: WidgetRegistry get() = controlsWidgets
}

/** The picker's pending choice, one per page, see [ThemeSelectionSource]. */
internal object ControlsKeys : KeyContribution {
    override val id: String = "controls.keys"
    override val label: String = "Theme controls"

    override fun declare(keys: ModuleKeys) {
        keys.source(ThemeSelectionSource, sample = { ThemeSelection(null) }) { page ->
            flowSource(MutableStateFlow(ThemeSelection(page.find(ThemesKeys.Active)?.state?.value)))
        }
    }
}
