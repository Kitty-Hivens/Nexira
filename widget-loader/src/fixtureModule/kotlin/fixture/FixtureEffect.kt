package fixture

import androidx.compose.runtime.Composable
import hivens.widget.api.ModuleSetting
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState

/** A screen effect carried by the fixture module, so the loader's tests can find one in a real jar. */
class FixtureEffect : ScreenEffect {

    override val id: String
        get() = if (System.getProperty(BLANK_ID) != null) " " else EFFECT_ID

    override val label: String = "fixture.label"

    override val settings: List<ModuleSetting>
        get() = when {
            System.getProperty(REPEATED_KEY) != null -> listOf(ModuleSetting.Toggle("flag", "fixture.flag"), ModuleSetting.Toggle("flag", "fixture.flag"))
            System.getProperty(RESERVED_KEY) != null -> listOf(ModuleSetting.Toggle(ModuleSetting.RESERVED_ENABLED, "fixture.flag"))
            else -> listOf(
                ModuleSetting.Toggle("flag", "fixture.flag"),
                ModuleSetting.Amount("amount", "fixture.amount", default = 0.25f),
            )
        }

    @Composable
    override fun Apply(state: ScreenEffectState, content: @Composable () -> Unit) = content()

    companion object {
        const val EFFECT_ID = "fixture.effect"

        /** Set, the effect names itself with nothing, as a hand written one might. */
        const val BLANK_ID = "fixture.effectBlankId"

        /** Set, the effect declares one setting key twice. */
        const val REPEATED_KEY = "fixture.effectRepeatedKey"

        /** Set, the effect declares the key the launcher keeps for itself. */
        const val RESERVED_KEY = "fixture.effectReservedKey"
    }
}
