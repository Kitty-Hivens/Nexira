package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Per-widget surface: the updateWidgetSurface transform + the
 * serialization round-trip / back-compat that the v2->v3 schema bump rests on.
 */
class LayoutGraphSurfaceTest {

    private val home = SurfaceId("home.new")
    private val main = SlotId("main")
    private val path = SlotPath(home, main)
    private val w = WidgetInstance(WidgetKind("k"), "i1", JsonObject(emptyMap()))
    private val graph = LayoutGraph(mapOf(home to SurfaceLayout(mapOf(main to SlotContent(listOf(w))))))

    private fun surfaceOf(g: LayoutGraph): SurfaceSpec? =
        g.surfaces[home]!!.slots[main]!!.widgets.first().surface

    @Test
    fun `updateWidgetSurface sets the surface on the target`() {
        val out = graph.updateWidgetSurface(path, "i1", SurfaceSpec(opacity = 0.4f, shape = SurfaceShape(corners = SurfaceCorners(all = 12f))))
        assertEquals(SurfaceSpec(opacity = 0.4f, shape = SurfaceShape(corners = SurfaceCorners(all = 12f))), surfaceOf(out))
    }

    @Test
    fun `a default (no-backing) chrome normalizes to null so the field stays absent`() {
        val out = graph.updateWidgetSurface(path, "i1", SurfaceSpec())
        assertNull(surfaceOf(out), "an all-zero chrome must not be persisted")
        assertSame(graph, out, "setting a no-op chrome on a null-chrome widget is identity")
    }

    @Test
    fun `updateWidgetSurface with null clears an existing backing`() {
        val withSurface = graph.updateWidgetSurface(path, "i1", SurfaceSpec(opacity = 0.5f))
        val cleared = withSurface.updateWidgetSurface(path, "i1", null)
        assertNull(surfaceOf(cleared))
    }

    @Test
    fun `updateWidgetSurface on a missing instance is a no-op`() {
        val out = graph.updateWidgetSurface(path, "does-not-exist", SurfaceSpec(opacity = 0.3f))
        assertSame(graph, out)
    }

    @Test
    fun `a widget with a surface round-trips through json`() {
        val json = Json { encodeDefaults = false }
        val instance = w.copy(surface = SurfaceSpec(opacity = 0.25f, shape = SurfaceShape(corners = SurfaceCorners(all = 8f)), padding = SurfaceInsets(all = 6f)))
        val text = json.encodeToString(WidgetInstance.serializer(), instance)
        assertEquals(instance, json.decodeFromString(WidgetInstance.serializer(), text))
    }

    @Test
    fun `a v2-shaped widget without a chrome field decodes with null chrome`() {
        // Forward-compat the schema bump rests on: old data has no chrome key.
        val json = Json { ignoreUnknownKeys = true }
        val v2 = """{"kind":"k","instance_id":"i1"}"""
        val decoded = json.decodeFromString(WidgetInstance.serializer(), v2)
        assertNull(decoded.surface)
        assertTrue(decoded.props.isEmpty())
    }
}
