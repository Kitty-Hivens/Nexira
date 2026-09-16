package hivens.widget.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class LayoutGraphMutationsTest {

    private val home = SurfaceId("home.new")
    private val main = SlotId("main")
    private val w1 = WidgetInstance(WidgetKind("a"), "i1", JsonObject(emptyMap()))
    private val w2 = WidgetInstance(WidgetKind("b"), "i2", JsonObject(emptyMap()))
    private val w3 = WidgetInstance(WidgetKind("c"), "i3", JsonObject(emptyMap()))

    private fun seed(vararg widgets: WidgetInstance): LayoutGraph = LayoutGraph(
        surfaces = mapOf(
            home to SurfaceLayout(slots = mapOf(main to SlotContent(widgets.toList()))),
        ),
    )

    private fun LayoutGraph.mainWidgets(): List<WidgetInstance> =
        surfaces[home]?.slots?.get(main)?.widgets ?: emptyList()

    private val rootPath: SlotPath = SlotPath(home, main)

    // ── Existing transform behavior (root-level via SlotPath) ─────────

    @Test
    fun `insertWidget at zero pushes existing widgets back`() {
        val out = seed(w1, w2).insertWidget(rootPath, w3, 0)
        assertEquals(listOf(w3, w1, w2), out.mainWidgets())
    }

    @Test
    fun `insertWidget past end coerces to append`() {
        val out = seed(w1).insertWidget(rootPath, w2, 999)
        assertEquals(listOf(w1, w2), out.mainWidgets())
    }

    @Test
    fun `insertWidget into unknown slot is a no-op identity return`() {
        val graph = seed(w1)
        val out = graph.insertWidget(SlotPath(home, SlotId("nope")), w2, 0)
        assertSame(graph, out, "no-op must return the same instance (no allocation)")
    }

    @Test
    fun `removeWidget filters by instanceId`() {
        val out = seed(w1, w2, w3).removeWidget(rootPath, "i2")
        assertEquals(listOf(w1, w3), out.mainWidgets())
    }

    @Test
    fun `removeWidget of unknown id is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.removeWidget(rootPath, "ghost"))
    }

    @Test
    fun `updateWidgetProps replaces props on the matching widget only`() {
        val props = buildJsonObject { put("title", "Hi") }
        val out = seed(w1, w2).updateWidgetProps(rootPath, "i2", props)
        assertEquals(props, out.mainWidgets().first { it.instanceId == "i2" }.props)
        assertEquals(JsonObject(emptyMap()), out.mainWidgets().first { it.instanceId == "i1" }.props)
    }

    @Test
    fun `updateWidgetProps on unknown instance is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.updateWidgetProps(rootPath, "ghost", buildJsonObject { put("x", 1) }))
    }

    @Test
    fun `updateWidgetProps on unknown slot is identity`() {
        val graph = seed(w1)
        assertSame(
            graph,
            graph.updateWidgetProps(SlotPath(home, SlotId("nope")), "i1", buildJsonObject { put("x", 1) }),
        )
    }

    // ── Slot mode: flow, and the unit a placement slot measures in ────

    @Test
    fun `setFlow changes the slot's arrangement`() {
        val out = seed(w1).setFlow(rootPath, FlowSpec.Row)
        assertEquals(FlowSpec.Row, out.surfaces[home]?.slots?.get(main)?.flow)
    }

    @Test
    fun `setFlow to the same value is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.setFlow(rootPath, FlowSpec.Column))
    }

    @Test
    fun `a grid is a horizontal flow that wraps into equal cells`() {
        val out = seed(w1).setFlow(rootPath, FlowSpec.grid(3))
        val flow = out.surfaces[home]?.slots?.get(main)?.flow!!
        assertEquals(true, flow.horizontal)
        assertEquals(3, flow.wrap)
        assertEquals(true, flow.uniform)
    }

    @Test
    fun `setGrid updates and clamps to the 0 to MAX range`() {
        assertEquals(3, seed(w1).setGrid(rootPath, 3).surfaces[home]?.slots?.get(main)?.grid)
        assertEquals(0, seed(w1).setGrid(rootPath, -4).surfaces[home]?.slots?.get(main)?.grid)
        assertEquals(GRID_MAX, seed(w1).setGrid(rootPath, 999).surfaces[home]?.slots?.get(main)?.grid)
    }

    @Test
    fun `setGrid does not rescale what is already placed`() {
        val placed = seed(w1).setPlacement(rootPath, "i1", Placement(x = 7f, y = 2f))
        val out = placed.setGrid(rootPath, 6)
        assertEquals(
            Placement(x = 7f, y = 2f),
            out.mainWidgets().first().placement,
            "the number means cells now rather than dp, and reinterpreting it is the user's call",
        )
    }

    @Test
    fun `setWidgetWeight sets weight on the matching widget only`() {
        val out = seed(w1, w2).setWidgetWeight(rootPath, "i2", 2f)
        assertEquals(2f, out.mainWidgets().first { it.instanceId == "i2" }.placement?.weight)
        assertNull(out.mainWidgets().first { it.instanceId == "i1" }.placement)
    }

    @Test
    fun `setWidgetWeight on unknown instance is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.setWidgetWeight(rootPath, "ghost", 1f))
    }

    @Test
    fun `setWidgetWeight coerces a negative weight to zero`() {
        val out = seed(w1).setWidgetWeight(rootPath, "i1", -5f)
        assertNull(
            out.mainWidgets().first().placement,
            "zero is the default, and an all-default record is no record at all",
        )
    }

    @Test
    fun `setWidgetWeight to the weight it already had is identity`() {
        // w1 carries no placement -- writing the default back must not mint one,
        // or every no-op transform starts allocating a new graph.
        val graph = seed(w1)
        assertSame(graph, graph.setWidgetWeight(rootPath, "i1", 0f))
    }

    // ── Placement ─────────────────────────────────────────────────────

    @Test
    fun `setPlacement sets it on the matching widget only`() {
        val p = Placement(x = 10f, y = 20f, width = 100f, height = 50f, z = 3)
        val out = seed(w1, w2).setPlacement(rootPath, "i2", p)
        assertEquals(p, out.mainWidgets().first { it.instanceId == "i2" }.placement)
        assertNull(out.mainWidgets().first { it.instanceId == "i1" }.placement)
    }

    @Test
    fun `setPlacement to the same record is identity`() {
        val placed = seed(w1).setPlacement(rootPath, "i1", Placement(x = 5f))
        assertSame(placed, placed.setPlacement(rootPath, "i1", Placement(x = 5f)))
    }

    @Test
    fun `setPlacement on unknown instance is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.setPlacement(rootPath, "ghost", Placement(x = 1f)))
    }

    @Test
    fun `setWidgetOffset then setWidgetSize compose without clobbering`() {
        val out = seed(w1)
            .setWidgetOffset(rootPath, "i1", 40f, 60f)
            .setWidgetSize(rootPath, "i1", 200f, 120f)
        assertEquals(
            Placement(x = 40f, y = 60f, width = 200f, height = 120f),
            out.mainWidgets().first { it.instanceId == "i1" }.placement,
        )
    }

    @Test
    fun `setWidgetSize coerces negatives to zero`() {
        val p = seed(w1).setWidgetOffset(rootPath, "i1", 1f, 1f)
            .setWidgetSize(rootPath, "i1", -10f, -5f)
            .mainWidgets().first().placement
        assertEquals(0f, p?.width)
        assertEquals(0f, p?.height)
    }

    @Test
    fun `setWidgetZ sets the layer`() {
        val out = seed(w1).setWidgetZ(rootPath, "i1", 5)
        assertEquals(5, out.mainWidgets().first().placement?.z)
    }

    @Test
    fun `setWidgetAnchor normalises a value it does not know`() {
        val out = seed(w1).setWidgetAnchor(rootPath, "i1", "sideways")
        assertNull(
            out.mainWidgets().first().placement,
            "an unrecognised anchor is the default one, and the default record is no record",
        )
        val real = seed(w1).setWidgetAnchor(rootPath, "i1", "  BottomEnd ")
        assertEquals(Placement.BOTTOM_END, real.mainWidgets().first().placement?.anchor)
    }

    // ── Seeding when a slot becomes a placement slot ───────────────────

    @Test
    fun `setFlow to null seeds a staggered grid onto the unplaced`() {
        val w4 = WidgetInstance(WidgetKind("d"), "i4", JsonObject(emptyMap()))
        val out = seed(w1, w2, w3, w4).setFlow(rootPath, null)
        val placed = out.mainWidgets().associate { it.instanceId to it.placement }
        assertEquals(Placement(x = 16f, y = 16f, z = 0), placed["i1"])
        assertEquals(Placement(x = 236f, y = 16f, z = 1), placed["i2"])
        assertEquals(Placement(x = 456f, y = 16f, z = 2), placed["i3"])
        assertEquals(Placement(x = 16f, y = 176f, z = 3), placed["i4"]) // wraps to the next row
        assertNull(out.surfaces[home]?.slots?.get(main)?.flow)
    }

    @Test
    fun `setFlow to null preserves an already-placed widget`() {
        val pre = seed(w1, w2).setPlacement(rootPath, "i1", Placement(x = 500f, y = 500f, z = 9))
        val out = pre.setFlow(rootPath, null)
        val placed = out.mainWidgets().associate { it.instanceId to it.placement }
        assertEquals(Placement(x = 500f, y = 500f, z = 9), placed["i1"]) // kept
        assertEquals(Placement(x = 236f, y = 16f, z = 1), placed["i2"])  // seeded at its index
    }

    @Test
    fun `setFlow to another flow only flips, no seeding`() {
        val out = seed(w1, w2).setFlow(rootPath, FlowSpec.Row)
        assertEquals(FlowSpec.Row, out.surfaces[home]?.slots?.get(main)?.flow)
        assertNull(out.mainWidgets().first { it.instanceId == "i1" }.placement)
        assertNull(out.mainWidgets().first { it.instanceId == "i2" }.placement)
    }

    @Test
    fun `setFlow to null twice is idempotent`() {
        val once = seed(w1, w2).setFlow(rootPath, null)
        assertSame(once, once.setFlow(rootPath, null))
    }

    @Test
    fun `seedPlacement staggers free placement into rows of three`() {
        assertEquals(Placement(x = 16f, y = 16f, z = 0), seedPlacement(0, grid = 0))
        assertEquals(Placement(x = 456f, y = 16f, z = 2), seedPlacement(2, grid = 0))
        assertEquals(Placement(x = 16f, y = 176f, z = 3), seedPlacement(3, grid = 0))
    }

    @Test
    fun `seedPlacement in a lattice takes the first free cell in reading order`() {
        val taken = listOf(
            WidgetInstance(WidgetKind("k"), "a", placement = Placement(x = 0f, y = 0f, width = 1f, height = 1f)),
            WidgetInstance(WidgetKind("k"), "b", placement = Placement(x = 1f, y = 0f, width = 1f, height = 1f)),
        )
        assertEquals(
            Placement(x = 0f, y = 1f, width = 1f, height = 1f),
            seedPlacement(index = 2, grid = 2, existing = taken),
            "two columns, both of row zero spoken for, so the next one is the row below",
        )
    }

    @Test
    fun `setFlow to a lattice seeds one-by-one cells in reading order`() {
        val w4  = WidgetInstance(WidgetKind("d"), "i4", JsonObject(emptyMap()))
        val out = seed(w1, w2, w3, w4).setGrid(rootPath, 2).setFlow(rootPath, null)
        val cells = out.mainWidgets().associate { it.instanceId to (it.placement?.x to it.placement?.y) }
        assertEquals(0f to 0f, cells["i1"])
        assertEquals(1f to 0f, cells["i2"])
        assertEquals(0f to 1f, cells["i3"])
        assertEquals(1f to 1f, cells["i4"])
    }

    // ── Lattice collision: snap, never evict, never compact ───────────

    private fun latticeContent(vararg pairs: Pair<String, Placement>): SlotContent =
        SlotContent(
            widgets = pairs.map { (id, p) -> WidgetInstance(WidgetKind("k"), id, JsonObject(emptyMap()), placement = p) },
            flow    = null,
            grid    = 4,
        )

    private fun cell(col: Int, row: Int, w: Int = 1, h: Int = 1) =
        Placement(x = col.toFloat(), y = row.toFloat(), width = w.toFloat(), height = h.toFloat())

    private fun SlotContent.placementOf(id: String): Placement? = widgets.first { it.instanceId == id }.placement

    @Test
    fun `placeInGrid snaps the moved widget to a free target, others fixed`() {
        val out = placeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(2, 0)), "a", cell(1, 0), columns = 4)
        assertEquals(cell(1, 0), out.placementOf("a"))
        assertEquals(cell(2, 0), out.placementOf("b"))
    }

    @Test
    fun `placeInGrid snaps to the nearest free cell when the target is occupied`() {
        // a -> b's cell (1,0): occupied, so a snaps to the nearest free one; b never moves.
        val out = placeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(1, 0)), "a", cell(1, 0), columns = 4)
        assertEquals(cell(0, 0), out.placementOf("a"))
        assertEquals(cell(1, 0), out.placementOf("b"))
    }

    @Test
    fun `placeInGrid never compacts -- gaps are preserved`() {
        // b floats at row 3; moving a must NOT pull b upward. This is a snap grid,
        // not a packer, and a gap somebody left is a gap they meant.
        val out = placeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(0, 3)), "a", cell(0, 0), columns = 4)
        assertEquals(cell(0, 0), out.placementOf("a"))
        assertEquals(cell(0, 3), out.placementOf("b"))
    }

    @Test
    fun `placeInGrid clamps span and column into the lattice`() {
        val out = placeInGrid(latticeContent("a" to cell(0, 0)), "a", cell(3, 0, w = 5), columns = 4)
        val a = out.placementOf("a")!!
        assertEquals(4f, a.width) // 5 clamped to the column count
        assertEquals(0f, a.x)     // and the anchor clamped into [0, columns - span]
    }

    @Test
    fun `placeInGrid is identity when nothing moves`() {
        val c = latticeContent("a" to cell(0, 0), "b" to cell(1, 0))
        assertSame(c, placeInGrid(c, "a", cell(0, 0), columns = 4))
    }

    @Test
    fun `resizeInGrid clamps the span so it cannot grow over a neighbour`() {
        val out = resizeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(1, 0)), "a", width = 3f, height = 1f, columns = 4)
        assertEquals(1f, out.placementOf("a")!!.width)
        assertEquals(cell(1, 0), out.placementOf("b"))
    }

    @Test
    fun `resizeInGrid grows into free space`() {
        val out = resizeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(3, 0)), "a", width = 2f, height = 2f, columns = 4)
        val a = out.placementOf("a")!!
        assertEquals(2f, a.width)
        assertEquals(2f, a.height)
    }

    @Test
    fun `placeWidgetInGrid on unknown instance is identity`() {
        val g = LayoutGraph(surfaces = mapOf(home to SurfaceLayout(slots = mapOf(main to latticeContent("a" to cell(0, 0))))))
        assertSame(g, g.placeWidgetInGrid(rootPath, "ghost", cell(1, 1), 4))
    }

    @Test
    fun `reorderInSlot swaps positions`() {
        val out = seed(w1, w2, w3).reorderInSlot(rootPath, fromIndex = 0, toIndex = 2)
        assertEquals(listOf(w2, w3, w1), out.mainWidgets())
    }

    @Test
    fun `reorderInSlot with same index is identity`() {
        val graph = seed(w1, w2)
        assertSame(graph, graph.reorderInSlot(rootPath, fromIndex = 0, toIndex = 0))
    }

    @Test
    fun `reorderInSlot out-of-range fromIndex is identity`() {
        val graph = seed(w1, w2)
        assertSame(graph, graph.reorderInSlot(rootPath, fromIndex = 5, toIndex = 0))
    }

    @Test
    fun `moveWidget across slots removes from source and inserts at target`() {
        val twoSlots = LayoutGraph(
            surfaces = mapOf(
                home to SurfaceLayout(slots = mapOf(
                    SlotId("top")    to SlotContent(listOf(w1, w2)),
                    SlotId("bottom") to SlotContent(listOf(w3)),
                )),
            ),
        )
        val out = twoSlots.moveWidget(
            from       = SlotPath(home, SlotId("top")),
            to         = SlotPath(home, SlotId("bottom")),
            instanceId = "i1",
            toIndex    = 0,
        )
        val top    = out.surfaces[home]!!.slots[SlotId("top")]!!.widgets
        val bottom = out.surfaces[home]!!.slots[SlotId("bottom")]!!.widgets
        assertEquals(listOf(w2),     top)
        assertEquals(listOf(w1, w3), bottom)
    }

    @Test
    fun `moveWidget within same slot delegates to reorderInSlot`() {
        val out = seed(w1, w2, w3).moveWidget(
            from       = rootPath,
            to         = rootPath,
            instanceId = "i3",
            toIndex    = 0,
        )
        assertEquals(listOf(w3, w1, w2), out.mainWidgets())
    }

    @Test
    fun `moveWidget of unknown instanceId is identity`() {
        val graph = seed(w1)
        val out = graph.moveWidget(
            from       = rootPath,
            to         = rootPath,
            instanceId = "ghost",
            toIndex    = 0,
        )
        assertSame(graph, out)
    }

    // ── Nested transforms ─────────────────────────────────────────────

    private val container = WidgetInstance(
        kind       = WidgetKind("container.group"),
        instanceId = "container1",
        children   = mapOf(SlotId("body") to SlotContent(listOf(w1, w2))),
    )

    private fun seedNested(): LayoutGraph = LayoutGraph(
        surfaces = mapOf(
            home to SurfaceLayout(slots = mapOf(main to SlotContent(listOf(container)))),
        ),
    )

    private val nestedBody: SlotPath = SlotPath(
        surface  = home,
        rootSlot = main,
        nested   = listOf(NestedSegment("container1", SlotId("body"))),
    )

    @Test
    fun `insertWidget at depth 1 grows the container's body slot`() {
        val out = seedNested().insertWidget(nestedBody, w3, 1)
        val containerNow = out.surfaces[home]!!.slots[main]!!.widgets[0]
        val bodyWidgets = containerNow.children[SlotId("body")]!!.widgets
        assertEquals(listOf(w1, w3, w2), bodyWidgets)
    }

    @Test
    fun `removeWidget at depth 1 strips a child without touching siblings`() {
        val out = seedNested().removeWidget(nestedBody, "i2")
        val containerNow = out.surfaces[home]!!.slots[main]!!.widgets[0]
        assertEquals(listOf(w1), containerNow.children[SlotId("body")]!!.widgets)
    }

    @Test
    fun `reorderInSlot at depth 1 reorders within the container`() {
        val out = seedNested().reorderInSlot(nestedBody, fromIndex = 0, toIndex = 1)
        val containerNow = out.surfaces[home]!!.slots[main]!!.widgets[0]
        assertEquals(listOf(w2, w1), containerNow.children[SlotId("body")]!!.widgets)
    }

    @Test
    fun `moveWidget out of nested slot up to root level`() {
        val out = seedNested().moveWidget(
            from       = nestedBody,
            to         = rootPath,
            instanceId = "i1",
            toIndex    = 0,
        )
        val rootWidgets = out.surfaces[home]!!.slots[main]!!.widgets
        assertEquals(2, rootWidgets.size)
        assertEquals("i1", rootWidgets[0].instanceId)
        // Container still present, body now has just w2.
        val containerNow = rootWidgets[1]
        assertEquals(listOf(w2), containerNow.children[SlotId("body")]!!.widgets)
    }

    @Test
    fun `moveWidget from root level into nested container`() {
        val withRootWidget = seedNested().insertWidget(rootPath, w3, 1)
        // Now: root = [container, w3]; container body = [w1, w2]
        val out = withRootWidget.moveWidget(
            from       = rootPath,
            to         = nestedBody,
            instanceId = "i3",
            toIndex    = 1,
        )
        val rootWidgets = out.surfaces[home]!!.slots[main]!!.widgets
        assertEquals(1, rootWidgets.size, "w3 leaves root slot")
        val bodyNow = rootWidgets[0].children[SlotId("body")]!!.widgets
        assertEquals(listOf(w1, w3, w2), bodyNow, "w3 landed at index 1 inside container")
    }

    @Test
    fun `moveWidget rejects a cycle (container into its own subtree)`() {
        // Try to drop the container into its own body slot -- would form
        // a self-cycle.
        val cyclePath = SlotPath(
            surface  = home,
            rootSlot = main,
            nested   = listOf(NestedSegment("container1", SlotId("body"))),
        )
        val graph = seedNested()
        val out = graph.moveWidget(
            from       = rootPath,
            to         = cyclePath,
            instanceId = "container1",
            toIndex    = 0,
        )
        assertSame(graph, out)
    }

    @Test
    fun `traverse returns content at the leaf path`() {
        val content = seedNested().traverse(nestedBody)
        assertNotNull(content)
        assertEquals(listOf(w1, w2), content.widgets)
    }

    @Test
    fun `traverse returns null for an unknown nested segment`() {
        val unknown = SlotPath(
            surface  = home,
            rootSlot = main,
            nested   = listOf(NestedSegment("does-not-exist", SlotId("body"))),
        )
        assertNull(seedNested().traverse(unknown))
    }

    @Test
    fun `walkInstances yields every widget across nesting`() {
        val ids = seedNested().walkInstances().map { it.instanceId }.toList()
        assertEquals(listOf("container1", "i1", "i2"), ids)
    }

    @Test
    fun `instanceIds collects ids tree-wide for a surface`() {
        val layout = SurfaceLayout(slots = mapOf(main to SlotContent(listOf(container))))
        assertEquals(setOf("container1", "i1", "i2"), layout.instanceIds())
    }

    @Test
    fun `removeInstanceIds strips matching widgets tree-wide`() {
        val layout = SurfaceLayout(
            slots = mapOf(
                SlotId("top") to SlotContent(listOf(w1, w2)),
                SlotId("bot") to SlotContent(listOf(container)),
            ),
        )
        val out = layout.removeInstanceIds(setOf("i1"))
        assertEquals(listOf("i2"), out.slots[SlotId("top")]!!.widgets.map { it.instanceId })
        // i1 nested inside the container's body slot is stripped too.
        val body = out.slots[SlotId("bot")]!!.widgets[0].children[SlotId("body")]!!.widgets
        assertEquals(listOf("i2"), body.map { it.instanceId })
    }

    @Test
    fun `resetSurface restores default and strips ids leaked to other surfaces`() {
        // The bug scenario: home's default widget (i1) was moved onto another
        // surface; resetting home re-adds i1, which must not collide.
        val defaultHome = SurfaceLayout(slots = mapOf(main to SlotContent(listOf(w1))))
        val graph = LayoutGraph(
            surfaces = mapOf(
                home to SurfaceLayout(slots = mapOf(main to SlotContent(listOf(w2)))),       // home edited away from default
                SurfaceId("library") to SurfaceLayout(slots = mapOf(                          // i1 leaked here
                    SlotId("body") to SlotContent(listOf(w1)),
                )),
            ),
        )
        val out = graph.resetSurface(home, defaultHome)
        assertEquals(listOf("i1"), out.surfaces[home]!!.slots[main]!!.widgets.map { it.instanceId })
        assertEquals(emptyList<String>(), out.surfaces[SurfaceId("library")]!!.slots[SlotId("body")]!!.widgets.map { it.instanceId })
        // The pre-fix bug would have produced two "i1" tree-wide -> uniqueness must hold.
        val ids = out.walkInstances().map { it.instanceId }.toList()
        assertEquals(ids.toSet().size, ids.size, "no duplicate instanceIds after reset")
    }

    @Test
    fun `resetSurface with a null default removes the surface entirely`() {
        assertNull(seed(w1).resetSurface(home, null).surfaces[home])
    }

    @Test
    fun `insertWidget into a nested slot the container did not declare is identity`() {
        // Pin the contract: the LayoutGraph mutator does NOT auto-create
        // missing child slot entries. The editor (EditModeController)
        // is responsible for pre-seeding container children from the
        // descriptor's declared slots when a container lands fresh from
        // the palette. Without that pre-seed, dropping a widget INTO
        // the freshly-added container would silently no-op here.
        val bareContainer = WidgetInstance(
            kind       = WidgetKind("container.group"),
            instanceId = "ctr-without-body",
            // children intentionally empty -- mimics a buggy editor
            // path that forgot to pre-seed.
        )
        val graph = LayoutGraph(
            surfaces = mapOf(
                home to SurfaceLayout(slots = mapOf(
                    main to SlotContent(listOf(bareContainer)),
                )),
            ),
        )
        val nestedPath = SlotPath(
            surface  = home,
            rootSlot = main,
            nested   = listOf(NestedSegment("ctr-without-body", SlotId("body"))),
        )
        assertSame(graph, graph.insertWidget(nestedPath, w1, 0))
    }
}
