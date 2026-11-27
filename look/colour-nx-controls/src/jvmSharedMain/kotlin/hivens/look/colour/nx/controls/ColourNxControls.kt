package hivens.look.colour.nx.controls

import hivens.i18n.api.I18nApi
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourApi
import hivens.look.icon.api.IconApi
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookState
import hivens.look.motion.api.MotionApi
import hivens.history.api.HistoryApi
import hivens.pages.api.PagesApi
import hivens.look.shape.api.ShapeApi
import hivens.look.surface.api.SurfaceApi
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelContext
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.flowSource
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The nx colour provider's controls as the application carries them: compiled in, its widgets drawn
 * by kind wherever a layout places them, and its keys brought in by a document that names [ID].
 *
 * It steers the themes module through that module's own keys, and asks the look's state only whether
 * nx draws the colour, which it publishes as [NxDrawing] for its widgets to be shown under. It owns no
 * setting and decides nothing of how anything is drawn.
 */
object ColourNxControls : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.colour.nx.controls"

    override val id: String = ID
    override val name: String = "controls.name"
    override val strings: ModuleStrings = ModuleStrings.resources(ColourNxControls::class.java, ID)
    override val requires: Map<String, ApiVersion> = mapOf(ThemesApi.ID to ThemesApi.VERSION, LookApi.ID to LookApi.VERSION)
    override val uses: Map<String, ApiVersion> = mapOf(ColourApi.ID to ColourApi.VERSION, MotionApi.ID to MotionApi.VERSION, ShapeApi.ID to ShapeApi.VERSION, SurfaceApi.ID to SurfaceApi.VERSION, IconApi.ID to IconApi.VERSION, I18nApi.ID to I18nApi.VERSION, PagesApi.ID to PagesApi.VERSION, HistoryApi.ID to HistoryApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(ControlsKeys)
    override val widgets: WidgetRegistry get() = controlsWidgets
}

/** The id of [NxDrawing], which every control names as the source it is shown under. */
internal const val NX_DRAWING_ID: String = "theme.nx.drawing"

/**
 * Whether the nx colour provider draws the colour now, by the look's state. The controls steer that
 * provider and nothing else, so each is shown under this and is absent where it is false: where another
 * family draws the colour, where the base answers hold, and where no look manager says what draws it.
 * An app source like any other, so a widget of another module may be shown under it by its id.
 */
internal val NxDrawing: SourceKey<Boolean> = SourceKey(NX_DRAWING_ID, ScopeLevel.App)

/** The picker's pending choice, one per page, see [ThemeSelectionSource], and whether nx draws, see [NxDrawing]. */
internal object ControlsKeys : KeyContribution {
    override val id: String = "controls.keys"
    override val label: String = "Theme controls"

    override fun declare(keys: ModuleKeys) {
        keys.source(ThemeSelectionSource, sample = { ThemeSelection(null) }) { page ->
            flowSource(MutableStateFlow(ThemeSelection(page.find(ThemesKeys.Active)?.state?.value)))
        }
        keys.source(NxDrawing, sample = { true }) { app -> nxDrawing(app) }
    }
}

/** [NxDrawing] as [app] answers it: the look's state as it changes, or false for good where nothing answers that. */
private fun nxDrawing(app: LevelContext): WidgetDataSource<Boolean> {
    val look = app.find(LookKeys.State)?.state ?: return flowSource(MutableStateFlow(false))
    val drawsNx = { state: LookState -> state.draws(Colour.id, ThemesApi.COLOUR_FAMILY) }
    return flowSource(look.map(drawsNx).stateIn(app.coroutineScope, SharingStarted.Eagerly, drawsNx(look.value)))
}
