package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a widget arrives is a name from a closed set, stored by its id, and an
 * instance that says nothing about it leaves no trace in the file.
 */
class EntranceTest {

    @Test
    fun `a character is read back from its id, whatever its case or padding`() {
        assertEquals(Entrance.Rise, Entrance.parse("rise"))
        assertEquals(Entrance.Settle, Entrance.parse("  Settle "))
        assertEquals(Entrance.None, Entrance.parse("none"))
    }

    @Test
    fun `a blank or unknown id names nothing, so the declaration shows through`() {
        assertNull(Entrance.parse(null))
        assertNull(Entrance.parse(""))
        // What a newer build might write and this one does not know.
        assertNull(Entrance.parse("spiral"))
    }

    @Test
    fun `an instance's arrival round-trips under the names the file uses`() {
        val json = Json { ignoreUnknownKeys = true }
        val motion = WidgetMotion(enter = "rise", delayMs = 140)
        val text = json.encodeToString(WidgetMotion.serializer(), motion)
        assertTrue("\"delay_ms\":140" in text, text)
        assertEquals(motion, json.decodeFromString(WidgetMotion.serializer(), text))
    }

    @Test
    fun `an arrival that says nothing is not written into the instance`() {
        val path = SlotPath(SurfaceId("home"), SlotId("main"))
        val graph = LayoutGraph(
            surfaces = mapOf(
                SurfaceId("home") to SurfaceLayout(
                    slots = mapOf(SlotId("main") to SlotContent(listOf(WidgetInstance(WidgetKind("w"), "w1")))),
                ),
            ),
        )
        val pinned = graph.updateWidgetMotion(path, "w1", WidgetMotion(enter = "settle"))
        assertEquals(WidgetMotion(enter = "settle"), pinned.traverse(path)!!.widgets.single().motion)

        val cleared = pinned.updateWidgetMotion(path, "w1", WidgetMotion())
        assertNull(cleared.traverse(path)!!.widgets.single().motion)
    }
}
