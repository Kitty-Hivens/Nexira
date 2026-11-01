package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A shipped preset arranges the surfaces it names and nothing else, and what it
 * puts there can live beside the rest of the layout.
 */
class BundledPresetsTest {

    private val default = DefaultLayout.load()

    @Test
    fun `every shipped preset loads`() {
        BundledPresets.IDS.forEach { id ->
            assertTrue(BundledPresets.load(id).surfaces.isNotEmpty(), "$id arranges nothing")
        }
    }

    @Test
    fun `a preset only names surfaces the layout has`() {
        BundledPresets.IDS.forEach { id ->
            val unknown = BundledPresets.load(id).surfaces.keys - default.surfaces.keys
            assertEquals(emptySet(), unknown, "$id names surfaces the layout does not have")
        }
    }

    @Test
    fun `applying one leaves every other surface as it was`() {
        BundledPresets.IDS.forEach { id ->
            val preset = BundledPresets.load(id)
            val applied = default.withSurfacesFrom(preset)
            (default.surfaces.keys - preset.surfaces.keys).forEach { sid ->
                assertEquals(default.surfaces[sid], applied.surfaces[sid], "$id changed ${sid.value}")
            }
        }
    }

    @Test
    fun `applying one leaves no instance id twice in the layout`() {
        BundledPresets.IDS.forEach { id ->
            val ids = default.withSurfacesFrom(BundledPresets.load(id)).walkInstances().map { it.instanceId }.toList()
            assertEquals(ids.size, ids.toSet().size, "$id duplicates an instance id")
        }
    }

    @Test
    fun `the type preset is the bundled Home, so it is the way back`() {
        val home = SurfaceId("home.new")
        fun kinds(g: LayoutGraph) = g.surfaces.getValue(home).allSlots().flatMap { it.widgets }.map { it.kind.value }.toList()
        assertEquals(kinds(default), kinds(BundledPresets.load("home-type")))
    }
}
