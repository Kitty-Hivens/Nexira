package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Per-widget backing (WidgetChrome): the updateWidgetChrome transform + the
 * serialization round-trip / back-compat that the v2->v3 schema bump rests on.
 */
class LayoutGraphChromeTest {

    private val home = SurfaceId("home.new")
    private val main = SlotId("main")
    private val path = SlotPath(home, main)
    private val w = WidgetInstance(WidgetKind("k"), "i1", JsonObject(emptyMap()))
    private val graph = LayoutGraph(mapOf(home to SurfaceLayout(mapOf(main to SlotContent(listOf(w))))))

    private fun chromeOf(g: LayoutGraph): WidgetChrome? =
        g.surfaces[home]!!.slots[main]!!.widgets.first().chrome

    @Test
    fun `updateWidgetChrome sets the backing on the target`() {
        val out = graph.updateWidgetChrome(path, "i1", WidgetChrome(glassAlphaPct = 40, cornerRadiusDp = 12))
        assertEquals(WidgetChrome(glassAlphaPct = 40, cornerRadiusDp = 12), chromeOf(out))
    }

    @Test
    fun `a default (no-backing) chrome normalizes to null so the field stays absent`() {
        val out = graph.updateWidgetChrome(path, "i1", WidgetChrome())
        assertNull(chromeOf(out), "an all-zero chrome must not be persisted")
        assertSame(graph, out, "setting a no-op chrome on a null-chrome widget is identity")
    }

    @Test
    fun `updateWidgetChrome with null clears an existing backing`() {
        val withChrome = graph.updateWidgetChrome(path, "i1", WidgetChrome(glassAlphaPct = 50))
        val cleared = withChrome.updateWidgetChrome(path, "i1", null)
        assertNull(chromeOf(cleared))
    }

    @Test
    fun `updateWidgetChrome on a missing instance is a no-op`() {
        val out = graph.updateWidgetChrome(path, "does-not-exist", WidgetChrome(glassAlphaPct = 30))
        assertSame(graph, out)
    }

    @Test
    fun `a widget with chrome round-trips through json`() {
        val json = Json { encodeDefaults = false }
        val instance = w.copy(chrome = WidgetChrome(glassAlphaPct = 25, cornerRadiusDp = 8, paddingDp = 6))
        val text = json.encodeToString(WidgetInstance.serializer(), instance)
        assertEquals(instance, json.decodeFromString(WidgetInstance.serializer(), text))
    }

    @Test
    fun `a v2-shaped widget without a chrome field decodes with null chrome`() {
        // Forward-compat the schema bump rests on: old data has no chrome key.
        val json = Json { ignoreUnknownKeys = true }
        val v2 = """{"kind":"k","instance_id":"i1"}"""
        val decoded = json.decodeFromString(WidgetInstance.serializer(), v2)
        assertNull(decoded.chrome)
        assertTrue(decoded.props.isEmpty())
    }
}
