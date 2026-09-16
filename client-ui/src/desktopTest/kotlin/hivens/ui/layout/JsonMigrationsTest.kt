package hivens.ui.layout

import hivens.widget.model.DefaultLayout
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The step that carries a saved arrangement across the collapse of five slot
 * orientations into two modes.
 *
 * It runs before the decoder on purpose, and that is the whole reason this file
 * exists: the shared Json ignores unknown keys, so a field the model no longer
 * declares is gone by the time a migration taking a `LayoutGraph` could look at
 * it. A structural step written on the decoded side would have compiled, run,
 * reported success, and silently returned every Row, Grid and Canvas in every
 * user's file as a plain column.
 *
 * So what is asserted here is not that the output parses. It is that each of the
 * five old shapes comes out the other side meaning the same thing.
 */
class JsonMigrationsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun migrate(graph: String, from: Int = 9): JsonObject =
        JsonMigrations.apply(from, json.parseToJsonElement(graph).jsonObject)

    private fun decode(graph: String, from: Int = 9): LayoutGraph =
        json.decodeFromJsonElement(LayoutGraph.serializer(), migrate(graph, from))

    private fun slotOf(g: LayoutGraph) = g.surfaces[SurfaceId("s")]!!.slots[SlotId("main")]!!

    private fun graphOf(slot: String) = """{"surfaces":{"s":{"slots":{"main":$slot}}}}"""

    // ── The five shapes ───────────────────────────────────────────────

    @Test
    fun `a column becomes a vertical flow`() {
        val slot = slotOf(decode(graphOf("""{"orientation":"Column","widgets":[]}""")))
        assertEquals(FlowSpec.Column, slot.flow)
        assertEquals(0, slot.grid)
    }

    @Test
    fun `a slot that never named an orientation becomes a vertical flow`() {
        // Twenty of the twenty-three slots in the bundled default were like this,
        // relying on the field's own default rather than writing it down.
        assertEquals(FlowSpec.Column, slotOf(decode(graphOf("""{"widgets":[]}"""))).flow)
    }

    @Test
    fun `a row becomes a horizontal flow`() {
        assertEquals(FlowSpec.Row, slotOf(decode(graphOf("""{"orientation":"Row","widgets":[]}"""))).flow)
    }

    @Test
    fun `a grid becomes a horizontal flow that wraps into equal cells`() {
        val slot = slotOf(decode(graphOf("""{"orientation":"Grid","gridColumns":3,"widgets":[]}""")))
        val flow = assertNotNull(slot.flow)
        assertEquals(true, flow.horizontal)
        assertEquals(3, flow.wrap, "the column count was the line length all along")
        assertEquals(true, flow.uniform)
        assertEquals(0, slot.grid, "a flow measures nothing")
    }

    @Test
    fun `a canvas becomes a placement slot measuring in dp`() {
        val slot = slotOf(decode(graphOf("""{"orientation":"Canvas","widgets":[]}""")))
        assertNull(slot.flow)
        assertEquals(0, slot.grid, "zero is the dp, which is what free placement was")
    }

    @Test
    fun `a cube grid becomes a placement slot measuring in cells`() {
        val slot = slotOf(decode(graphOf("""{"orientation":"CubeGrid","gridColumns":4,"widgets":[]}""")))
        assertNull(slot.flow)
        assertEquals(4, slot.grid, "the lattice it already described, now said once")
    }

    @Test
    fun `an orientation this build never wrote still lands somewhere sane`() {
        // The old enum folded an unknown wire value to a sentinel that rendered as
        // a column. The migration answers the same way rather than dropping the slot.
        assertEquals(FlowSpec.Column, slotOf(decode(graphOf("""{"orientation":"Masonry","widgets":[]}"""))).flow)
    }

    // ── What a widget carried ─────────────────────────────────────────

    private fun widget(extra: String) = """{"kind":"k","instance_id":"i",$extra}"""

    @Test
    fun `a weighted widget in a flow keeps its weight`() {
        val slot = slotOf(decode(graphOf("""{"orientation":"Row","widgets":[${widget("\"weight\":2.0")}]}""")))
        assertEquals(2f, slot.widgets.single().placement?.weight)
    }

    @Test
    fun `a resized widget in a flow keeps the size as its bound and drops the offset`() {
        val w = widget(""""canvas":{"x":40.0,"y":80.0,"width":300.0,"height":120.0,"z":3}""")
        val p = assertNotNull(slotOf(decode(graphOf("""{"orientation":"Column","widgets":[$w]}"""))).widgets.single().placement)
        assertEquals(300f, p.width)
        assertEquals(120f, p.height)
        assertEquals(0f, p.x, "the offset meant nothing in a flow, and carrying it would make a visit look like a decision")
        assertEquals(0f, p.y)
        assertEquals(0, p.z)
    }

    @Test
    fun `a widget with nothing placed carries no placement`() {
        val slot = slotOf(decode(graphOf("""{"orientation":"Column","widgets":[{"kind":"k","instance_id":"i"}]}""")))
        assertNull(slot.widgets.single().placement, "unplaced has to stay expressible, or seeding cannot tell whom to seed")
    }

    @Test
    fun `a canvas widget keeps its offset, size and layer`() {
        val w = widget(""""canvas":{"x":40.0,"y":80.0,"width":300.0,"height":120.0,"z":3}""")
        val p = assertNotNull(slotOf(decode(graphOf("""{"orientation":"Canvas","widgets":[$w]}"""))).widgets.single().placement)
        assertEquals(Placement(x = 40f, y = 80f, width = 300f, height = 120f, z = 3), p)
    }

    @Test
    fun `a cube widget's cell becomes its position and span`() {
        val w = widget(""""cell":{"col":2,"row":1,"colSpan":2,"rowSpan":3,"z":5}""")
        val p = assertNotNull(slotOf(decode(graphOf("""{"orientation":"CubeGrid","gridColumns":4,"widgets":[$w]}"""))).widgets.single().placement)
        assertEquals(Placement(x = 2f, y = 1f, width = 2f, height = 3f, z = 5), p)
    }

    @Test
    fun `a widget carrying all three fields keeps the one its slot was reading`() {
        // The old model let an instance hold weight, canvas and cell at once, and
        // flipping a slot back and forth produced exactly that. The answer here is
        // the answer the renderer was already giving on screen.
        val w = widget(""""weight":2.0,"canvas":{"x":9.0,"y":9.0},"cell":{"col":3,"row":3}""")
        val cube = slotOf(decode(graphOf("""{"orientation":"CubeGrid","gridColumns":4,"widgets":[$w]}"""))).widgets.single().placement
        assertEquals(3f, cube?.x)
        assertEquals(0f, cube?.weight)

        val flow = slotOf(decode(graphOf("""{"orientation":"Row","widgets":[$w]}"""))).widgets.single().placement
        assertEquals(2f, flow?.weight)
        assertEquals(0f, flow?.x)
    }

    @Test
    fun `a key this step does not own is copied across untouched`() {
        val w = widget(""""surface":{"fill":"raised"},"somethingNewer":7""")
        val migrated = migrate(graphOf("""{"orientation":"Column","widgets":[$w]}"""))
        val out = migrated["surfaces"]!!.jsonObject["s"]!!.jsonObject["slots"]!!
            .jsonObject["main"]!!.jsonObject["widgets"]!!
        assertEquals(true, out.toString().contains("somethingNewer"), "the step rewrites a shape, not the whole record")
        val decoded = slotOf(decode(graphOf("""{"orientation":"Column","widgets":[$w]}""")))
        assertEquals("raised", decoded.widgets.single().surface?.fill, "and the plane survives it")
    }

    @Test
    fun `a container's children migrate too`() {
        val child = """{"orientation":"Canvas","widgets":[${widget(""""canvas":{"x":5.0,"y":6.0}""")}]}"""
        val container = """{"kind":"c","instance_id":"outer","children":{"body":$child}}"""
        val slot = slotOf(decode(graphOf("""{"orientation":"Column","widgets":[$container]}""")))
        val body = assertNotNull(slot.widgets.single().children[SlotId("body")])
        assertNull(body.flow, "a nested canvas is a nested placement slot")
        assertEquals(5f, body.widgets.single().placement?.x)
    }

    // ── The ladder itself ─────────────────────────────────────────────

    @Test
    fun `a graph already at the current schema is handed back untouched`() {
        val graph = json.parseToJsonElement(graphOf("""{"widgets":[]}""")).jsonObject
        assertSame(graph, JsonMigrations.apply(LayoutReconcile.CURRENT_SCHEMA, graph))
    }

    @Test
    fun `a structurally invalid version is refused rather than looped over`() {
        val graph = json.parseToJsonElement(graphOf("""{"widgets":[]}""")).jsonObject
        try {
            JsonMigrations.apply(0, graph)
            error("a version below 1 must be refused")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message?.contains("schema_version"))
        }
    }

    // ── The real file ─────────────────────────────────────────────────

    @Test
    fun `the bundled default as it shipped at schema 8 migrates into the one that ships now`() {
        // The strongest check available: the previous release's actual resource,
        // carried through this step, has to come out as the file the build now
        // carries. It is the difference between a migration that parses and a
        // migration that means the same thing.
        val old = javaClass.getResourceAsStream("/layout/default-layout-schema8.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val envelope = json.parseToJsonElement(old).jsonObject
        val migrated = JsonMigrations.apply(8, envelope["graph"]!!.jsonObject)
        val decoded = json.decodeFromJsonElement(LayoutGraph.serializer(), migrated)

        assertEquals(DefaultLayout.load(), decoded, "the shipped bundle and the migrated one must be the same graph")
    }

    @Test
    fun `the two rows in the shipped bundle survive as horizontal flows`() {
        // Named because it is the case a decode-side migration would have lost in
        // silence, and the only two of twenty-three slots where it would show.
        val bundle = DefaultLayout.load()
        val horizontal = bundle.surfaces.values
            .flatMap { it.slots.values }
            .count { it.flow?.horizontal == true }
        assertEquals(2, horizontal)
    }
}
