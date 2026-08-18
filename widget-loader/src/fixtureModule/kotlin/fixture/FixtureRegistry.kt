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

    override fun all(): Map<WidgetKind, WidgetDescriptor> = map
    override fun get(kind: WidgetKind): WidgetDescriptor? = map[kind]

    companion object {
        const val FIXTURE_KIND = "fixture.widget"
    }
}
