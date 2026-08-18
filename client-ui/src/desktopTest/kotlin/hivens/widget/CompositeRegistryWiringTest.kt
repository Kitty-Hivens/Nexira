package hivens.widget

import hivens.widget.api.CompositeWidgetRegistry
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.loader.WidgetModuleLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The sibling test pins the contents of the built-in registry. This one pins the
// wiring around it: what the composition root binds is a composite over the
// built-in registry plus whatever loaded from the user's widgets directory, and
// the launcher has to be correct when that directory holds nothing at all --
// which is every launcher that has never had a widget module installed.
class CompositeRegistryWiringTest {

    @Test
    fun `a launcher with no widget modules is exactly the built-in registry`() {
        val empty = Files.createTempDirectory("no-widget-modules-")
        try {
            val scan = WidgetModuleLoader(empty.resolve("widgets")).scan()
            val registry = CompositeWidgetRegistry(listOf(GeneratedWidgetRegistry) + scan.loaded.map { it.registry })

            assertEquals(GeneratedWidgetRegistry.all().keys, registry.all().keys)
            assertEquals(emptyList(), registry.shadowed)
        } finally {
            Files.deleteIfExists(empty)
        }
    }

    @Test
    fun `the built-in registry claims no id twice`() {
        // The composite resolves a collision by dropping the later claim, and it
        // can only do that safely if the first source is internally consistent.
        // KSP already rejects duplicates within one module; this is what says so
        // out loud at the point the invariant is relied on.
        val composite = CompositeWidgetRegistry(listOf(GeneratedWidgetRegistry, GeneratedWidgetRegistry))

        assertEquals(GeneratedWidgetRegistry.all().size, composite.all().size)
        assertTrue(composite.shadowed.isNotEmpty(), "the same registry twice must report as shadowed, or the check is inert")
    }
}
