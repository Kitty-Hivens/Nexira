package hivens.look.motion.nx.controls

import androidx.compose.runtime.Composable
import hivens.i18n.api.I18nApi
import hivens.i18n.api.strings
import hivens.look.icon.api.IconApi
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookState
import hivens.look.motion.api.Motion
import hivens.look.motion.api.MotionApi
import hivens.look.motion.nx.MotionNxKeys
import hivens.look.motion.nx.MotionNxModule
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
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.Widget
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The nx motion provider's controls as the application carries them: compiled in, its widget drawn by
 * kind wherever a layout places it, and its keys brought in by a document that names [ID].
 *
 * It steers the provider through the provider's own keys, and asks the look's state only whether nx
 * draws the motion, which it publishes as [NxMotionDrawing] for its widget to be shown under. It owns no
 * setting and decides nothing of how anything moves.
 */
object MotionNxControls : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.motion.nx.controls"

    override val id: String = ID
    override val name: String = "controls.name"
    override val strings: ModuleStrings = ModuleStrings.resources(MotionNxControls::class.java, ID)
    override val requires: Map<String, ApiVersion> = mapOf(MotionNxModule.ID to MotionNxModule.version, LookApi.ID to LookApi.VERSION)
    override val uses: Map<String, ApiVersion> = mapOf(MotionApi.ID to MotionApi.VERSION, IconApi.ID to IconApi.VERSION, I18nApi.ID to I18nApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(MotionControlsKeys)
    override val widgets: WidgetRegistry get() = controlsWidgets
}

/** The id of [NxMotionDrawing], which the switch names as the source it is shown under. */
internal const val NX_MOTION_DRAWING_ID: String = "motion.nx.drawing"

/**
 * Whether the nx motion provider draws the motion now, by the look's state. The switch steers that
 * provider and nothing else, so it is absent where this is false: where another family draws the
 * motion, where the base answers hold, and where no look manager says what draws it.
 */
internal val NxMotionDrawing: SourceKey<Boolean> = SourceKey(NX_MOTION_DRAWING_ID, ScopeLevel.App)

/** Whether nx draws the motion, see [NxMotionDrawing]. */
internal object MotionControlsKeys : KeyContribution {
    override val id: String = "motion.controls.keys"
    override val label: String = "Motion controls"

    override fun declare(keys: ModuleKeys) {
        keys.source(NxMotionDrawing, sample = { true }) { app -> nxMotionDrawing(app) }
    }
}

/** [NxMotionDrawing] as [app] answers it: the look's state as it changes, or false for good where nothing answers that. */
private fun nxMotionDrawing(app: LevelContext): WidgetDataSource<Boolean> {
    val look = app.find(LookKeys.State)?.state ?: return flowSource(MutableStateFlow(false))
    val drawsNx = { state: LookState -> state.draws(Motion.id, MotionNxModule.FAMILY) }
    return flowSource(look.map(drawsNx).stateIn(app.coroutineScope, SharingStarted.Eagerly, drawsNx(look.value)))
}

// Whether the movement that only decorates holds still, beside the blur in the Appearance island
// because it is the same kind of choice: decoration that costs something, switched off in one place
// rather than widget by widget. Draws nothing where the provider's key has no answer, since a switch
// that steers nothing is clutter. The puppet id is the one the island had, kept so a driver finds the
// switch where it was. Already there when its page opens, as the rows around it are.
@Widget(id = "motion.reduce", displayName = "widget.motion.reduce", enter = "none", shownWhen = NX_MOTION_DRAWING_ID)
@Composable
fun ReduceMotionWidget() {
    val reduce = rememberScopedSource(MotionNxKeys.ReduceMotion).valueOrNull() ?: return
    val set = rememberScopedCommand(MotionNxKeys.SetReduceMotion)
    val s = strings(MotionNxControls.ID)
    val change: (Boolean) -> Unit = { set?.invoke(it) }
    NxToggle(
        label           = s.text("settingsReduceMotion"),
        checked         = reduce,
        description     = s.text("settingsReduceMotionDesc"),
        icon            = NxIcon.Speed,
        accent          = NxColor.lead(),
        onCheckedChange = change,
    )
    PuppetToggle("background.reduceMotion", reduce, onValueChange = change)
}
