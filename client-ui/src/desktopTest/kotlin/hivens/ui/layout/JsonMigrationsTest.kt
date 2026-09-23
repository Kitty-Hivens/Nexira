package hivens.ui.layout

import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun slotOf(g: LayoutGraph) = g.surfaces[SurfaceId("s")]!!.slotsOf(FamilyId.GENERAL)[SlotId("main")]!!

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
        val out = migrated["surfaces"]!!.jsonObject["s"]!!.jsonObject["families"]!!
            .jsonObject["general"]!!.jsonObject["slots"]!!
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

    /** The previous release's resource, exactly as it shipped. */
    private fun shippedAtSchema8(): JsonObject =
        json.parseToJsonElement(
            javaClass.getResourceAsStream("/layout/default-layout-schema8.json")!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        ).jsonObject

    private fun migratedBundle(): LayoutGraph =
        json.decodeFromJsonElement(
            LayoutGraph.serializer(),
            JsonMigrations.apply(8, shippedAtSchema8()["graph"]!!.jsonObject),
        )

    @Test
    fun `the shipped bundle has the shape the step produces`() {
        // What this proves and what it does not.
        //
        // It used to compare the two graphs whole, which only held while the bundle
        // and the captured fixture described the same set of widgets. They do not
        // and cannot: the fixture is the previous release's resource, frozen, and
        // every widget added since is in one and not the other. Held to equality the
        // test fails on the next feature rather than on a mistake, and the only way
        // to keep it green is to re-capture the fixture -- which throws away the one
        // thing the fixture is for.
        //
        // So what is compared is the SHAPE: the surfaces, the families inside them,
        // the slots inside those, and each slot's arrangement. That is what the step
        // rewrites, and a bundle hand-edited into a shape the step does not produce
        // is what this is here to catch. Whether the contents survive the crossing
        // is the test below, which derives what it expects from the old bytes.
        val shipped = DefaultLayout.load()
        val produced = migratedBundle()
        assertEquals(produced.surfaces.keys, shipped.surfaces.keys, "a surface appeared or vanished")
        produced.surfaces.forEach { (sid, oldLayout) ->
            val now = shipped.surfaces[sid]!!
            oldLayout.families.forEach { (fid, family) ->
                val nowFamily = assertNotNull(now.family(fid), "family $sid/${fid.value} is not in the shipped bundle")
                assertEquals(
                    family.slots.keys, nowFamily.slots.keys,
                    "the shipped bundle's slots for $sid/${fid.value} are not the ones the step produces",
                )
                family.slots.forEach { (slotId, content) ->
                    val nowContent = nowFamily.slots[slotId]!!
                    assertEquals(content.flow, nowContent.flow, "$sid/${fid.value}/${slotId.value} changed arrangement")
                    assertEquals(content.grid, nowContent.grid, "$sid/${fid.value}/${slotId.value} changed its unit")
                }
            }
        }
    }

    // ── Families ──────────────────────────────────────────────────────

    @Test
    fun `a file written before families comes back as the general one`() {
        val migrated = migrate(graphOf("""{"orientation":"Row","widgets":[]}"""), from = 9)
        val families = migrated["surfaces"]!!.jsonObject["s"]!!.jsonObject["families"]!!.jsonObject
        assertEquals(setOf("general"), families.keys)
        assertNotNull(families["general"]!!.jsonObject["slots"]!!.jsonObject["main"])
        assertNull(migrated["surfaces"]!!.jsonObject["s"]!!.jsonObject["slots"], "the flat slot map must not be left behind")
    }

    @Test
    fun `a surface that already names its families is not wrapped a second time`() {
        // A hand-edited file, or one this build wrote and then re-read under a lower
        // stamp. Wrapping twice would bury the reader's whole arrangement one level
        // deeper, under a family nothing renders.
        val already = """{"surfaces":{"s":{"families":{"general":{"slots":{"main":{"widgets":[],"flow":null,"grid":0}}}}}}}"""
        val migrated = JsonMigrations.apply(10, json.parseToJsonElement(already).jsonObject)
        val families = migrated["surfaces"]!!.jsonObject["s"]!!.jsonObject["families"]!!.jsonObject
        assertEquals(setOf("general"), families.keys)
        assertNotNull(families["general"]!!.jsonObject["slots"]!!.jsonObject["main"])
    }

    @Test
    fun `a nested container keeps addressing its children by slot, not by family`() {
        // A family belongs to a surface, which is the thing code navigates and
        // swaps. A widget nested inside one is already inside whichever family is
        // showing it, so wrapping its children would invent a level nothing reads.
        val child = """{"orientation":"Column","widgets":[]}"""
        val container = """{"kind":"c","instance_id":"outer","children":{"body":$child}}"""
        val slot = slotOf(decode(graphOf("""{"orientation":"Column","widgets":[$container]}""")))
        assertNotNull(slot.widgets.single().children[SlotId("body")])
    }

    @Test
    fun `a surface carrying no slots at all still comes back with a general family`() {
        // The editor writes an empty surface when the reader clears one out, and the
        // decoder would otherwise read the result as a surface with no families,
        // which no path renders and the reconciler cannot seed into.
        val migrated = JsonMigrations.apply(10, json.parseToJsonElement("""{"surfaces":{"s":{}}}""").jsonObject)
        val families = migrated["surfaces"]!!.jsonObject["s"]!!.jsonObject["families"]!!.jsonObject
        assertEquals(setOf("general"), families.keys)
    }

    @Test
    fun `every surface, slot, widget and id in the old bundle survives`() {
        assertSurvives(shippedAtSchema8(), 8)
    }

    @Test
    fun `the surfaces the server list stood on are dropped, and only those`() {
        val migrated = JsonMigrations.apply(
            11,
            json.parseToJsonElement(
                """{"surfaces":{"home.classic":{"families":{}},"server.details":{"families":{}},"home.new":{"families":{}}}}""",
            ).jsonObject,
        )
        assertEquals(setOf("home.new"), migrated["surfaces"]!!.jsonObject.keys)
    }

    @Test
    fun `a file with none of the retired surfaces or kinds is handed back untouched`() {
        val before = json.parseToJsonElement("""{"surfaces":{"home.new":{"families":{}}}}""").jsonObject
        assertSame(before, JsonMigrations.apply(11, before))
    }

    /**
     * A retired widget dropped onto a surface that SURVIVES.
     *
     * The registry-aware pass reaps unknown kinds only after a schema bump and
     * only on the layout file, so a preset carrying one writes it into a graph
     * that is already current, where nothing will ever reap it. Reaping here
     * covers both readers, because both run this ladder.
     */
    @Test
    fun `a retired widget is reaped from a surface that stays`() {
        val migrated = JsonMigrations.apply(
            11,
            json.parseToJsonElement(
                """
                {"surfaces":{"appshell.rightrail":{"families":{"general":{"slots":{"news":{"widgets":[
                  {"kind":"server.details.banner","instance_id":"a"},
                  {"kind":"container.tabs","instance_id":"b","children":{"body":{"widgets":[
                    {"kind":"home.classic.content","instance_id":"c"},
                    {"kind":"notes.scratch","instance_id":"d"}
                  ]}}}
                ]}}}}}}}
                """.trimIndent(),
            ).jsonObject,
        )
        val decoded = json.decodeFromJsonElement(LayoutGraph.serializer(), migrated)
        val slot = decoded.surfaces[SurfaceId("appshell.rightrail")]!!
            .slotsOf(FamilyId.GENERAL)[SlotId("news")]!!
        assertEquals(listOf("b"), slot.widgets.map { it.instanceId }, "the retired banner goes")
        assertEquals(
            listOf("d"),
            slot.widgets.single().children[SlotId("body")]!!.widgets.map { it.instanceId },
            "and so does one nested inside a container",
        )
    }

    @Test
    fun `a file carrying every old shape survives all of them`() {
        // The bundle exercises three of the five: twenty slots leaning on the
        // field's own default, one Column and two Rows, no gridColumns, no canvas,
        // no cell, no nesting. Every branch that is actually the substance of this
        // step is untouched by it, so the same invariants run over a file that has
        // one of each, including two a hand edit can produce.
        assertSurvives(fixture("legacy-sampler-schema9.json"), 9)
    }

    @Test
    fun `a hand-edited column count of zero lands where the old renderer put it`() {
        val migrated = json.decodeFromJsonElement(
            LayoutGraph.serializer(),
            JsonMigrations.apply(9, fixture("legacy-sampler-schema9.json")["graph"]!!.jsonObject),
        )
        val slots = migrated.surfaces[SurfaceId("sampler")]!!.slotsOf(FamilyId.GENERAL)

        // The old renderer read the count as coerceAtLeast(1), so a zero drew a
        // one-column grid. Carried across raw it would have become a flow that
        // never wraps, which is a row.
        val gridZero = slots[SlotId("gridZero")]!!
        assertEquals(1, gridZero.flow?.wrap, "a grid of zero columns is a grid of one")
        assertEquals(true, gridZero.flow?.uniform)

        // And on the lattice side a zero would have meant free placement in dp, so
        // a cell address of (3, 2) would have been read as three dp by two.
        val cubeZero = slots[SlotId("cubeZero")]!!
        assertNull(cubeZero.flow)
        assertEquals(1, cubeZero.grid, "a lattice of zero columns is a lattice of one")
        assertEquals(3f, cubeZero.widgets.single().placement?.x, "the cell address is still cells")
    }

    @Test
    fun `a nested container's canvas child keeps its placement`() {
        val migrated = json.decodeFromJsonElement(
            LayoutGraph.serializer(),
            JsonMigrations.apply(9, fixture("legacy-sampler-schema9.json")["graph"]!!.jsonObject),
        )
        val body = migrated.surfaces[SurfaceId("sampler")]!!.slotsOf(FamilyId.GENERAL)[SlotId("nested")]!!
            .widgets.single().children[SlotId("body")]!!
        assertNull(body.flow, "the nested canvas came back a flow")
        val p = assertNotNull(body.widgets.single().placement)
        assertEquals(5f, p.x)
        assertEquals(70f, p.width)
        assertEquals(3, p.z)
    }

    // ── Home slot padding (schema 13) ─────────────────────────────────

    @Test
    fun `home placement widgets gain padding for the slot gutter the screen dropped`() {
        // The offset is left alone; the compensation is per-widget padding on the side
        // the anchor counts from, so the arrangement holds while the gutter becomes the
        // widget's own to change.
        val graph = """
            {"surfaces":{"home.new":{"families":{"general":{"slots":{"main":{
              "flow":null,"grid":0,"widgets":[
                {"kind":"home.new.hero","instance_id":"h","placement":{"anchor":"topStart","x":100.0,"y":50.0}},
                {"kind":"home.new.player.wave","instance_id":"p","placement":{"anchor":"bottomEnd","x":0.0,"y":0.0}}
              ]}}}}}}}
        """.trimIndent()
        val g = json.decodeFromJsonElement(
            LayoutGraph.serializer(),
            JsonMigrations.apply(12, json.parseToJsonElement(graph).jsonObject),
        )
        val main = g.surfaces[SurfaceId("home.new")]!!.slotsOf(FamilyId.GENERAL)[SlotId("main")]!!
        val hero = assertNotNull(main.widgets.first { it.instanceId == "h" }.placement)
        assertEquals(24f, hero.padding.start, "a start anchor compensates on the start side")
        assertEquals(20f, hero.padding.top, "a top anchor compensates on the top side")
        assertNull(hero.padding.end)
        assertNull(hero.padding.bottom)
        assertEquals(100f, hero.x, "the offset is untouched")
        val player = assertNotNull(main.widgets.first { it.instanceId == "p" }.placement)
        assertEquals(24f, player.padding.end, "an end anchor compensates on the end side")
        assertEquals(20f, player.padding.bottom, "a bottom anchor compensates on the bottom side")
        assertNull(player.padding.start)
        assertNull(player.padding.top)
    }

    @Test
    fun `the home flow default is left alone by the padding step`() {
        // A flow slot has offsets nowhere to compensate; its gutter comes from the
        // seed, so the step must not touch it or add a second one.
        val graph = """
            {"surfaces":{"home.new":{"families":{"general":{"slots":{"main":{
              "flow":{"direction":"vertical","wrap":0,"uniform":false},"grid":0,"widgets":[
                {"kind":"home.new.welcome","instance_id":"w","placement":{"padding":{"start":24.0}}}
              ]}}}}}}}
        """.trimIndent()
        val g = json.decodeFromJsonElement(
            LayoutGraph.serializer(),
            JsonMigrations.apply(12, json.parseToJsonElement(graph).jsonObject),
        )
        val w = assertNotNull(
            g.surfaces[SurfaceId("home.new")]!!.slotsOf(FamilyId.GENERAL)[SlotId("main")]!!.widgets.single().placement,
        )
        assertEquals(24f, w.padding.start, "the seed's own padding is untouched")
        assertNull(w.padding.top, "and nothing was added")
    }

    private fun fixture(name: String): JsonObject =
        json.parseToJsonElement(
            javaClass.getResourceAsStream("/layout/$name")!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        ).jsonObject

    /**
     * Nothing named in [envelope] is lost, and every mode comes out as its
     * successor. Expectations are counted off the old bytes at run time, so
     * nothing here can agree with a mistake made on the other side.
     *
     * Both walks recurse into containers. They did not, and the two sides only
     * matched because the bundle has no container in it: the first one to arrive
     * would have failed this for a reason that was never about the migration.
     */
    private val RETIRED_AT_12 = setOf("home.classic", "server.details")

    private fun assertSurvives(envelope: JsonObject, from: Int) {
        // The two surfaces schema 12 retires are excluded on purpose: this asserts
        // that nothing is lost by ACCIDENT, and a step whose whole job is to drop
        // something would otherwise read as the loss it exists to make.
        val oldSurfaces = JsonObject(
            envelope["graph"]!!.jsonObject["surfaces"]!!.jsonObject
                .filterKeys { it !in RETIRED_AT_12 },
        )
        val migrated = json.decodeFromJsonElement(
            LayoutGraph.serializer(),
            JsonMigrations.apply(from, envelope["graph"]!!.jsonObject),
        )

        assertEquals(oldSurfaces.keys, migrated.surfaces.keys.map { it.value }.toSet(), "a surface went missing")

        val oldIds = mutableListOf<String>()
        val oldWeights = mutableMapOf<String, Float>()
        var oldHorizontal = 0
        var oldPlacement = 0

        fun walkOld(slot: JsonObject) {
            when (slot["orientation"]?.jsonPrimitive?.contentOrNull) {
                "Row", "Grid" -> oldHorizontal++
                "Canvas", "CubeGrid" -> oldPlacement++
            }
            slot["widgets"]?.jsonArray?.forEach { w ->
                val obj = w.jsonObject
                val id = obj["instance_id"]!!.jsonPrimitive.content
                oldIds += id
                obj["weight"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0f }?.let { oldWeights[id] = it }
                obj["children"]?.jsonObject?.values?.forEach { walkOld(it.jsonObject) }
            }
        }
        oldSurfaces.values.forEach { layout ->
            layout.jsonObject["slots"]!!.jsonObject.values.forEach { walkOld(it.jsonObject) }
        }

        val newIds = mutableListOf<String>()
        val newWeights = mutableMapOf<String, Float>()
        var newHorizontal = 0
        var newPlacement = 0

        fun walkNew(slot: SlotContent) {
            when {
                slot.flow == null -> newPlacement++
                slot.flow?.horizontal == true -> newHorizontal++
            }
            slot.widgets.forEach { w ->
                newIds += w.instanceId
                w.placement?.weight?.takeIf { it > 0f }?.let { newWeights[w.instanceId] = it }
                w.children.values.forEach { walkNew(it) }
            }
        }
        migrated.surfaces.values.forEach { layout -> layout.slotsOf(FamilyId.GENERAL).values.forEach { walkNew(it) } }

        oldSurfaces.forEach { (surfaceId, layout) ->
            assertEquals(
                layout.jsonObject["slots"]!!.jsonObject.keys,
                migrated.surfaces[SurfaceId(surfaceId)]!!.slotsOf(FamilyId.GENERAL).keys.map { it.value }.toSet(),
                "a slot went missing from $surfaceId",
            )
        }
        assertEquals(oldIds.sorted(), newIds.sorted(), "a widget went missing or was duplicated")
        assertEquals(oldHorizontal, newHorizontal, "a row or a grid did not stay horizontal")
        assertEquals(oldPlacement, newPlacement, "a canvas or a lattice changed mode")
        assertEquals(oldWeights, newWeights, "a weight was lost, gained or moved to another widget")
    }
}
