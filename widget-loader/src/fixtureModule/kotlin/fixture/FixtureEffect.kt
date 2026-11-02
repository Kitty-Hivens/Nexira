package fixture

import androidx.compose.runtime.Composable
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState

/** A screen effect carried by the fixture module, so the loader's tests can find one in a real jar. */
class FixtureEffect : ScreenEffect {

    override val id: String
        get() = if (System.getProperty(BLANK_ID) != null) " " else EFFECT_ID

    override val displayName: String = "Fixture Effect"

    @Composable
    override fun Apply(state: ScreenEffectState, content: @Composable () -> Unit) = content()

    companion object {
        const val EFFECT_ID = "fixture.effect"

        /** Set, the effect names itself with nothing, as a hand written one might. */
        const val BLANK_ID = "fixture.effectBlankId"
    }
}
