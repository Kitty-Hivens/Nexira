package fixture

import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.flowSource
import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow

/** The keys the fixture module answers, so the loader's tests can find a key contribution in a real jar. */
class FixtureKeys : KeyContribution {

    override val id: String = KEYS_ID

    override val label: String = "fixture.keys"

    override fun declare(keys: ModuleKeys) {
        keys.source(GREETING, sample = { "sample" }) { flowSource(MutableStateFlow("hello from the jar")) }
    }

    companion object {
        const val KEYS_ID = "fixture.keys"

        /** A source of the fixture's own, answered at the app level. */
        val GREETING = SourceKey<String>("fixture.greeting")
    }
}
