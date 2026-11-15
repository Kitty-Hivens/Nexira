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

    /** The thread's context class loader while the loader made this, by name. */
    private val loaderAtLoad: String? = contextLoaderName()

    override fun declare(keys: ModuleKeys) {
        val atDeclare = contextLoaderName()
        keys.source(GREETING, sample = { "sample" }) { flowSource(MutableStateFlow("hello from the jar")) }
        keys.source(CONTEXT_LOADERS, sample = { mapOf("load" to loaderAtLoad, "declare" to atDeclare, "sample" to contextLoaderName()) }) {
            flowSource(MutableStateFlow(mapOf("load" to loaderAtLoad, "declare" to atDeclare, "factory" to contextLoaderName())))
        }
    }

    companion object {
        const val KEYS_ID = "fixture.keys"

        /** A source of the fixture's own, answered at the app level. */
        val GREETING = SourceKey<String>("fixture.greeting")

        /**
         * The name of the thread's context class loader at each place the fixture's code
         * ran, so the tests can see it was the module's own every time.
         */
        val CONTEXT_LOADERS = SourceKey<Map<String, String?>>("fixture.contextLoaders")
    }
}

private fun contextLoaderName(): String? = Thread.currentThread().contextClassLoader?.name
