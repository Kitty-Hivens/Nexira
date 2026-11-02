package hivens.widget.api

import androidx.compose.runtime.Composable

/**
 * What the interface asks of a screen effect while it draws.
 *
 * [intensity] is the person's own setting, 0 to 1, and an effect is expected to scale its look by
 * it rather than treat 1 as the only strength. [reducedMotion] is the interface's reduced motion
 * setting: an effect that moves must hold still, and may keep a still version of its look.
 */
data class ScreenEffectState(
    val intensity: Float,
    val reducedMotion: Boolean,
)

/**
 * A look applied to the whole interface at once, which a widget cannot be: a widget draws inside
 * its slot and never sees what is drawn around it, while an effect is handed the interface itself
 * as [Apply]'s content and draws that, distorted or recoloured, in its place.
 *
 * A module carries zero or more of these beside its widgets, or instead of them, through the
 * `META-INF/services/hivens.widget.api.ScreenEffect` service file, the way it carries its registry.
 * The person picks one in the settings. None is applied until they do, and the launcher draws
 * [Apply]'s content itself again the moment the module is switched off or leaves.
 *
 * [Apply] must call [content] exactly once and must not read or keep anything outside what it is
 * handed. It runs in the composition on every frame the effect moves, so what it does per frame is
 * what the interface costs.
 */
interface ScreenEffect {
    /** Stable across versions of the module, since the person's choice is stored by it. */
    val id: String

    /** What the settings list it as. */
    val displayName: String

    @Composable
    fun Apply(state: ScreenEffectState, content: @Composable () -> Unit)
}
