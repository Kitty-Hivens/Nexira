package fixture

import androidx.compose.runtime.Composable
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.KSerializer

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
        // Resolved lazily in a generated registry, so a class the module lacks fails here.
        override val propsSerializer: KSerializer<*>?
            get() = if (System.getProperty(FAIL_PROPS) != null) throw NoClassDefFoundError("fixture/MissingProps") else null
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

        /** Set, reading the widget's props class fails as a class missing from the module would. */
        const val FAIL_PROPS = "fixture.failProps"
    }
}
