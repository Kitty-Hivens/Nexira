package hivens.look.surface.nx.controls

import androidx.compose.runtime.Composable
import hivens.i18n.api.strings
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookState
import hivens.look.surface.api.Surface
import hivens.look.surface.nx.SurfaceNxKeys
import hivens.look.surface.nx.SurfaceNxModule
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.NxColor
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelContext
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.flowSource
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.Widget
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The nx surface provider's controls as the application carries them: compiled in, its widget drawn by
 * kind wherever a layout places it, and its keys brought in by a document that names [ID].
 *
 * It steers the provider through the provider's own keys, and asks the look's state only whether nx
 * draws the planes, which it publishes as [NxSurfaceDrawing] for its widget to be shown under. It owns
 * no setting and decides nothing of how a plane is drawn.
 */
object SurfaceNxControls : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.surface.nx.controls"

    override val id: String = ID
    override val name: String = "controls.name"
    override val strings: ModuleStrings = ModuleStrings.resources(SurfaceNxControls::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(SurfaceControlsKeys)
    override val widgets: WidgetRegistry get() = controlsWidgets
}

/** The id of [NxSurfaceDrawing], which the switch names as the source it is shown under. */
internal const val NX_SURFACE_DRAWING_ID: String = "surface.nx.drawing"

/**
 * Whether the nx surface provider draws the planes now, by the look's state. The switch steers that
 * provider and nothing else, so it is absent where this is false: where another family draws the
 * planes, where the base answers hold, and where no look manager says what draws them.
 */
internal val NxSurfaceDrawing: SourceKey<Boolean> = SourceKey(NX_SURFACE_DRAWING_ID, ScopeLevel.App)

/** Whether nx draws the planes, see [NxSurfaceDrawing]. */
internal object SurfaceControlsKeys : KeyContribution {
    override val id: String = "surface.controls.keys"
    override val label: String = "Surface controls"

    override fun declare(keys: ModuleKeys) {
        keys.source(NxSurfaceDrawing, sample = { true }) { app -> nxSurfaceDrawing(app) }
    }
}

/** [NxSurfaceDrawing] as [app] answers it: the look's state as it changes, or false for good where nothing answers that. */
private fun nxSurfaceDrawing(app: LevelContext): WidgetDataSource<Boolean> {
    val look = app.find(LookKeys.State)?.state ?: return flowSource(MutableStateFlow(false))
    val drawsNx = { state: LookState -> state.draws(Surface.id, SurfaceNxModule.FAMILY) }
    return flowSource(look.map(drawsNx).stateIn(app.coroutineScope, SharingStarted.Eagerly, drawsNx(look.value)))
}

// Whether planes blur what is behind them. A backdrop filter is recomputed every frame by construction,
// so the one place it can be spent or saved is here rather than per surface. Draws nothing where the
// provider's key has no answer, since a switch that steers nothing is clutter. The puppet id is the one
// the Appearance island had, kept so a driver finds the switch where it was. Already there when its page
// opens, as the rows around it are.
@Widget(id = "surface.blur", displayName = "widget.surface.blur", enter = "none", shownWhen = NX_SURFACE_DRAWING_ID)
@Composable
fun SurfaceBlurWidget() {
    val blur = rememberScopedSource(SurfaceNxKeys.Blur).valueOrNull() ?: return
    val set = rememberScopedCommand(SurfaceNxKeys.SetBlur)
    val s = strings(SurfaceNxControls.ID)
    val change: (Boolean) -> Unit = { set?.invoke(it) }
    NxToggle(
        label           = s.text("settingsSurfaceBlur"),
        checked         = blur,
        description     = s.text("settingsSurfaceBlurDesc"),
        icon            = NxIcon.Layers,
        accent          = NxColor.lead(),
        onCheckedChange = change,
    )
    PuppetToggle("background.surfaceBlur", blur, onValueChange = change)
}
