package hivens.widget

import hivens.module.pixelplayer.generated.PixelPlayerWidgetRegistry
import hivens.widget.api.CompositeWidgetRegistry
import hivens.widget.generated.GeneratedWidgetRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The sibling test pins the built-in registry. This one pins what the
// application actually binds, which is the composite over every registry in the
// build -- the two drift apart the moment a second module contributes widgets.
class CompositeRegistryWiringTest {

    private val sources = listOf(GeneratedWidgetRegistry, PixelPlayerWidgetRegistry)

    @Test
    fun `no widget id is claimed by two registries`() {
        // First source wins, so a collision does not break the build or throw --
        // the later widget just never appears. Only this check turns that into a
        // failure anyone notices.
        val composite = CompositeWidgetRegistry(sources)
        assertEquals(
            emptyList(),
            composite.shadowed,
            "two registries claim the same widget id; the later one is dropped and its widget will " +
                "silently never render",
        )
    }

    @Test
    fun `the composite carries every widget of every source`() {
        val composite = CompositeWidgetRegistry(sources)
        assertEquals(
            sources.flatMap { it.all().keys }.toSet(),
            composite.all().keys,
            "the composite lost widgets it was given",
        )
    }

    @Test
    fun `the contributed module reaches the composite`() {
        // Guards the check above from passing vacuously: if the second registry
        // ever compiles to empty, the merge and the collision test both still
        // pass while widget injection has quietly stopped working.
        assertTrue(
            PixelPlayerWidgetRegistry.all().isNotEmpty(),
            "the out-of-tree registry is empty -- KSP stopped emitting it, or the module left the build",
        )
    }
}
