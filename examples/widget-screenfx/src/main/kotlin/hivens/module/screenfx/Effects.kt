package hivens.module.screenfx

import hivens.widget.api.ScreenEffect
import androidx.compose.runtime.Composable
import hivens.widget.api.ScreenEffectState

/**
 * The three effects, as the service file names them: each class is a thin handle on its shader, since
 * the loader instantiates a service by its class.
 */
class TripScreenEffect : ScreenEffect by Delegate(TripEffect.effect)

class RetroScreenEffect : ScreenEffect by Delegate(RetroEffect.effect)

class GlitchScreenEffect : ScreenEffect by Delegate(GlitchEffect.effect)

private class Delegate(private val inner: ShaderEffect) : ScreenEffect {
    override val id: String get() = inner.id
    override val displayName: String get() = inner.displayName

    @Composable
    override fun Apply(state: ScreenEffectState, content: @Composable () -> Unit) = inner.Apply(state, content)
}
