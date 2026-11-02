package hivens.ui.widgets.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState

/**
 * Draws [content] through the screen effect named [selected], or plainly when none is, or when the
 * module that carried it is off or gone.
 *
 * The content is one movable composition, so switching an effect on or off moves it between being
 * drawn bare and being drawn by the effect without recomposing it from nothing: the screen the
 * person is on, what they typed, the open panels all stay.
 */
@Composable
fun ScreenEffectHost(
    effects: List<ScreenEffect>,
    selected: String?,
    intensity: Float,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val latest by rememberUpdatedState(content)
    val shown = remember { movableContentOf { latest() } }
    val effect = selected?.let { id -> effects.firstOrNull { it.id == id } }
    if (effect == null) {
        shown()
    } else {
        effect.Apply(ScreenEffectState(intensity = intensity.coerceIn(0f, 1f), reducedMotion = reducedMotion), shown)
    }
}
