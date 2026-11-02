package hivens.ui.widgets.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import hivens.widget.api.ModuleValues
import hivens.widget.api.ScreenEffectState
import hivens.ui.widgets.modules.WidgetModules.ModuleEffect

/**
 * Draws [content] through every screen effect the person has switched on, or plainly when none is,
 * or when the module that carried it is off or gone.
 *
 * [settings] is what the person set, by [ModuleEffect.scope]. Effects that are on are drawn one
 * inside the next in the order the modules load: the first is outermost, so it bends what the
 * others drew.
 *
 * The content is one movable composition, so switching an effect on or off moves it between being
 * drawn bare and being drawn by the effects without recomposing it from nothing: the screen the
 * person is on, what they typed, the open panels all stay.
 */
@Composable
fun ScreenEffectHost(
    effects: List<ModuleEffect>,
    settings: Map<String, Map<String, String>>,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val latest by rememberUpdatedState(content)
    val shown = remember { movableContentOf { latest() } }
    val on = effects.filter { settings[it.scope]?.get(WidgetModules.EFFECT_ENABLED)?.toBooleanStrictOrNull() == true }
    Layer(on, 0, settings, reducedMotion, shown)
}

@Composable
private fun Layer(
    on: List<ModuleEffect>,
    index: Int,
    settings: Map<String, Map<String, String>>,
    reducedMotion: Boolean,
    inner: @Composable () -> Unit,
) {
    val entry = on.getOrNull(index)
    if (entry == null) {
        inner()
        return
    }
    // Keyed by what the effect is, so one switched on or off in the middle leaves the others' state alone.
    key(entry.scope) {
        val state = ScreenEffectState(ModuleValues(entry.effect.settings, settings[entry.scope].orEmpty()), reducedMotion)
        entry.effect.Apply(state) { Layer(on, index + 1, settings, reducedMotion, inner) }
    }
}
