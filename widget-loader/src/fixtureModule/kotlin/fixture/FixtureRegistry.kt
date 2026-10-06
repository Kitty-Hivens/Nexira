package fixture

import androidx.compose.runtime.Composable
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

/**
 * A widget module the loader's tests can actually load.
 *
 * It lives in its own source set so it is compiled into a jar and NOT onto the
 * test classpath. A fixture the tests could already see would be resolved
 * through the parent loader, and every test would pass without the jar being
 * opened at all.
 */
class FixtureRegistry : WidgetRegistry {

    private val descriptor = object : WidgetDescriptor {
        override val kind = WidgetKind(FIXTURE_KIND)
        override val displayName = "Fixture Widget"
        override val removable = true
        @Composable override fun Render(instance: WidgetInstance) = Unit
    }

    private val map = mapOf(descriptor.kind to descriptor)

    override fun all(): Map<WidgetKind, WidgetDescriptor> =
        if (System.getProperty(FAIL_LISTING) != null) throw IllegalStateException("the fixture module failed to list on purpose") else map
    override fun get(kind: WidgetKind): WidgetDescriptor? = map[kind]

    /** Fails from inside the module's own code, for tracing a failure back to the module. */
    fun explode(): Nothing = throw IllegalStateException("the fixture module failed on purpose")

    companion object {
        const val FIXTURE_KIND = "fixture.widget"

        /** Set, the registry throws when asked for its widgets, as a broken module's would. */
        const val FAIL_LISTING = "fixture.failListing"
    }
}
