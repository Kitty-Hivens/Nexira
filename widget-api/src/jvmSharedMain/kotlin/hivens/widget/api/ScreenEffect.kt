package hivens.widget.api

import androidx.compose.runtime.Composable

/**
 * What the interface asks of a screen effect while it draws.
 *
 * [values] are the person's own settings for this effect, read through the keys [ScreenEffect.settings]
 * declared, and an effect is expected to scale its look by them rather than treat full strength as the
 * only one. [reducedMotion] is the interface's reduced motion setting: an effect that moves must hold
 * still, and may keep a still version of its look.
 */
data class ScreenEffectState(
    val values: ModuleValues,
    val reducedMotion: Boolean,
)

/**
 * A look applied to the whole interface at once, which a widget cannot be: a widget draws inside
 * its slot and never sees what is drawn around it, while an effect is handed the interface itself
 * as [Apply]'s content and draws that, distorted or recoloured, in its place.
 *
 * A module carries zero or more of these beside its widgets, or instead of them, through the
 * `META-INF/services/hivens.widget.api.ScreenEffect` service file, the way it carries its registry.
 * The launcher lists each in the settings with a switch and the controls [settings] declares, and
 * stores what the person chose. Nothing is applied until they switch one on, several on together
 * are drawn one inside the next in the order the modules load, and the launcher draws [Apply]'s
 * content itself again the moment an effect is switched off or its module leaves.
 *
 * [Apply] must call [content] exactly once and must not read or keep anything outside what it is
 * handed. It runs in the composition on every frame the effect moves, so what it does per frame is
 * what the interface costs.
 */
interface ScreenEffect {
    /** Stable across versions of the module, since the person's choices are stored by it. */
    val id: String

    /** What the settings call it: a key into the module's [ModuleStrings], shown as written without an entry. */
    val label: String

    /** What the person can adjust, drawn and stored by the launcher. Nothing by default. */
    val settings: List<ModuleSetting> get() = emptyList()

    @Composable
    fun Apply(state: ScreenEffectState, content: @Composable () -> Unit)
}
