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

    // ── Phase G: slot orientation + grid columns + widget weight ──────

    @Test
    fun `setSlotOrientation changes the slot orientation`() {
        val out = seed(w1).setSlotOrientation(rootPath, SlotOrientation.Row)
        assertEquals(SlotOrientation.Row, out.surfaces[home]?.slots?.get(main)?.orientation)
    }

    @Test
    fun `setSlotOrientation to the same value is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.setSlotOrientation(rootPath, SlotOrientation.Column))
    }

    @Test
    fun `setGridColumns updates and clamps to the 1 to MAX range`() {
        assertEquals(3, seed(w1).setGridColumns(rootPath, 3).surfaces[home]?.slots?.get(main)?.gridColumns)
        assertEquals(1, seed(w1).setGridColumns(rootPath, 0).surfaces[home]?.slots?.get(main)?.gridColumns)
        assertEquals(GRID_COLUMNS_MAX, seed(w1).setGridColumns(rootPath, 99).surfaces[home]?.slots?.get(main)?.gridColumns)
    }

    @Test
    fun `setWidgetWeight sets weight on the matching widget only`() {
        val out = seed(w1, w2).setWidgetWeight(rootPath, "i2", 2f)
        assertEquals(2f, out.mainWidgets().first { it.instanceId == "i2" }.weight)
        assertEquals(0f, out.mainWidgets().first { it.instanceId == "i1" }.weight)
    }

    @Test
    fun `setWidgetWeight on unknown instance is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.setWidgetWeight(rootPath, "ghost", 1f))
    }

    @Test
    fun `setWidgetWeight coerces a negative weight to zero`() {
        val out = seed(w1).setWidgetWeight(rootPath, "i1", -5f)
        assertEquals(0f, out.mainWidgets().first { it.instanceId == "i1" }.weight)
    }

    @Test
    fun `setWidgetWeight to the same weight is identity`() {
        // w1 defaults to weight 0f -- re-setting 0f must not allocate a new graph.
        val graph = seed(w1)
        assertSame(graph, graph.setWidgetWeight(rootPath, "i1", 0f))
    }

    // ── Canvas placement (Canvas slot mode) ───────────────────────────

    @Test
    fun `setCanvasPlacement sets placement on the matching widget only`() {
        val out = seed(w1, w2).setCanvasPlacement(rootPath, "i2", CanvasPlacement(10f, 20f, 100f, 50f, 3))
        assertEquals(CanvasPlacement(10f, 20f, 100f, 50f, 3), out.mainWidgets().first { it.instanceId == "i2" }.canvas)
        assertNull(out.mainWidgets().first { it.instanceId == "i1" }.canvas)
    }

    @Test
    fun `setCanvasPlacement to the same placement is identity`() {
        val placed = seed(w1).setCanvasPlacement(rootPath, "i1", CanvasPlacement(x = 5f))
        assertSame(placed, placed.setCanvasPlacement(rootPath, "i1", CanvasPlacement(x = 5f)))
    }

    @Test
    fun `setCanvasPlacement on unknown instance is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.setCanvasPlacement(rootPath, "ghost", CanvasPlacement(x = 1f)))
    }

    @Test
    fun `setWidgetOffset then setWidgetSize compose without clobbering`() {
        val out = seed(w1)
            .setWidgetOffset(rootPath, "i1", 40f, 60f)
            .setWidgetSize(rootPath, "i1", 200f, 120f)
        assertEquals(
            CanvasPlacement(x = 40f, y = 60f, width = 200f, height = 120f),
            out.mainWidgets().first { it.instanceId == "i1" }.canvas,
        )
    }

    @Test
    fun `setWidgetSize coerces negatives to zero`() {
        val p = seed(w1).setWidgetSize(rootPath, "i1", -10f, -5f).mainWidgets().first { it.instanceId == "i1" }.canvas
        assertEquals(0f, p?.width)
        assertEquals(0f, p?.height)
    }

    @Test
    fun `setWidgetZ sets the layer`() {
        val out = seed(w1).setWidgetZ(rootPath, "i1", 5)
        assertEquals(5, out.mainWidgets().first { it.instanceId == "i1" }.canvas?.z)
    }

    // ── Seed-on-switch (flip to Canvas) ───────────────────────────────

    @Test
    fun `setSlotOrientation to Canvas seeds a staggered grid onto null-placement widgets`() {
        val w4 = WidgetInstance(WidgetKind("d"), "i4", JsonObject(emptyMap()))
        val out = seed(w1, w2, w3, w4).setSlotOrientation(rootPath, SlotOrientation.Canvas)
        val placed = out.mainWidgets().associate { it.instanceId to it.canvas }
        assertEquals(CanvasPlacement(x = 16f, y = 16f, z = 0), placed["i1"])
        assertEquals(CanvasPlacement(x = 236f, y = 16f, z = 1), placed["i2"])
        assertEquals(CanvasPlacement(x = 456f, y = 16f, z = 2), placed["i3"])
        assertEquals(CanvasPlacement(x = 16f, y = 176f, z = 3), placed["i4"]) // wraps to row 1
        assertEquals(SlotOrientation.Canvas, out.surfaces[home]?.slots?.get(main)?.orientation)
    }

    @Test
    fun `setSlotOrientation to Canvas preserves an already-placed widget`() {
        val pre = seed(w1, w2).setCanvasPlacement(rootPath, "i1", CanvasPlacement(x = 500f, y = 500f, z = 9))
        val out = pre.setSlotOrientation(rootPath, SlotOrientation.Canvas)
        val placed = out.mainWidgets().associate { it.instanceId to it.canvas }
        assertEquals(CanvasPlacement(x = 500f, y = 500f, z = 9), placed["i1"]) // kept
        assertEquals(CanvasPlacement(x = 236f, y = 16f, z = 1), placed["i2"])  // seeded at its index
    }

    @Test
    fun `setSlotOrientation to a non-Canvas orientation only flips, no seeding`() {
        val out = seed(w1, w2).setSlotOrientation(rootPath, SlotOrientation.Row)
        assertEquals(SlotOrientation.Row, out.surfaces[home]?.slots?.get(main)?.orientation)
        assertNull(out.mainWidgets().first { it.instanceId == "i1" }.canvas)
        assertNull(out.mainWidgets().first { it.instanceId == "i2" }.canvas)
    }

    @Test
    fun `setSlotOrientation to the current orientation is identity`() {
        val graph = seed(w1) // defaults to Column
        assertSame(graph, graph.setSlotOrientation(rootPath, SlotOrientation.Column))
    }

    @Test
    fun `setSlotOrientation to Canvas twice is idempotent`() {
        val once = seed(w1, w2).setSlotOrientation(rootPath, SlotOrientation.Canvas)
        assertSame(once, once.setSlotOrientation(rootPath, SlotOrientation.Canvas))
    }

    @Test
    fun `seededCanvasPlacement wraps into columns`() {
        assertEquals(CanvasPlacement(x = 16f, y = 16f, z = 0), seededCanvasPlacement(0))
        assertEquals(CanvasPlacement(x = 456f, y = 16f, z = 2), seededCanvasPlacement(2))
        assertEquals(CanvasPlacement(x = 16f, y = 176f, z = 3), seededCanvasPlacement(3))
    }

    // ── CubeGrid: cell seeding + snap placement (no overlap, no compaction) ──

    private fun cubeContent(vararg pairs: Pair<String, GridCell>): SlotContent =
        SlotContent(
            widgets     = pairs.map { (id, c) -> WidgetInstance(WidgetKind("k"), id, JsonObject(emptyMap()), cell = c) },
            orientation = SlotOrientation.CubeGrid,
            gridColumns = 4,
        )

    private fun SlotContent.cellOf(id: String): GridCell? = widgets.first { it.instanceId == id }.cell

    @Test
    fun `setSlotOrientation to CubeGrid seeds 1x1 cells in flow order`() {
        val w4  = WidgetInstance(WidgetKind("d"), "i4", JsonObject(emptyMap()))
        val out = seed(w1, w2, w3, w4).setSlotOrientation(rootPath, SlotOrientation.CubeGrid)
        val cells = out.mainWidgets().associate { it.instanceId to it.cell }
        // gridColumns default = 2 -> two per row, row-major.
        assertEquals(GridCell(0, 0), cells["i1"])
        assertEquals(GridCell(1, 0), cells["i2"])
        assertEquals(GridCell(0, 1), cells["i3"])
        assertEquals(GridCell(1, 1), cells["i4"])
        assertEquals(SlotOrientation.CubeGrid, out.surfaces[home]?.slots?.get(main)?.orientation)
    }

    @Test
    fun `placeInCubeGrid snaps the moved widget to a free target, others fixed`() {
        val out = placeInCubeGrid(cubeContent("a" to GridCell(0, 0), "b" to GridCell(2, 0)), "a", GridCell(col = 1, row = 0), columns = 4)
        assertEquals(GridCell(1, 0), out.cellOf("a"))
        assertEquals(GridCell(2, 0), out.cellOf("b"))
    }

    @Test
    fun `placeInCubeGrid snaps to the nearest free cell when the target is occupied`() {
        // a -> b's cell (1,0): occupied, so a snaps to the nearest free cell (0,0); b never moves.
        val out = placeInCubeGrid(cubeContent("a" to GridCell(0, 0), "b" to GridCell(1, 0)), "a", GridCell(col = 1, row = 0), columns = 4)
        assertEquals(GridCell(0, 0), out.cellOf("a"))
        assertEquals(GridCell(1, 0), out.cellOf("b"))
    }

    @Test
    fun `placeInCubeGrid never compacts -- gaps are preserved`() {
        // b floats at row 3; moving a must NOT pull b upward (this is a snap grid, not a packer).
        val out = placeInCubeGrid(cubeContent("a" to GridCell(0, 0), "b" to GridCell(0, 3)), "a", GridCell(0, 0), columns = 4)
        assertEquals(GridCell(0, 0), out.cellOf("a"))
        assertEquals(GridCell(0, 3), out.cellOf("b"))
    }

    @Test
    fun `placeInCubeGrid clamps span and column into the grid`() {
        val out = placeInCubeGrid(cubeContent("a" to GridCell(0, 0)), "a", GridCell(col = 3, row = 0, colSpan = 5), columns = 4)
        val a = out.cellOf("a")!!
        assertEquals(4, a.colSpan) // 5 clamped to columns
        assertEquals(0, a.col)     // col clamped into [0, columns - span]
    }

    @Test
    fun `placeInCubeGrid is identity when nothing moves`() {
        val c = cubeContent("a" to GridCell(0, 0), "b" to GridCell(1, 0))
        assertSame(c, placeInCubeGrid(c, "a", GridCell(0, 0), columns = 4))
    }

    @Test
    fun `resizeInCubeGrid clamps the span so it cannot grow over a neighbour`() {
        // a (0,0) asks for 3 wide but b sits at (1,0): a stays 1 wide, b never moves.
        val out = resizeInCubeGrid(cubeContent("a" to GridCell(0, 0), "b" to GridCell(1, 0)), "a", colSpan = 3, rowSpan = 1, columns = 4)
        assertEquals(1, out.cellOf("a")!!.colSpan)
        assertEquals(GridCell(1, 0), out.cellOf("b"))
    }

    @Test
    fun `resizeInCubeGrid grows into free space`() {
        val out = resizeInCubeGrid(cubeContent("a" to GridCell(0, 0), "b" to GridCell(3, 0)), "a", colSpan = 2, rowSpan = 2, columns = 4)
        val a = out.cellOf("a")!!
        assertEquals(2, a.colSpan)
        assertEquals(2, a.rowSpan)
    }

    @Test
    fun `placeWidgetInCell on unknown instance is identity`() {
        val g = LayoutGraph(surfaces = mapOf(home to SurfaceLayout(slots = mapOf(main to cubeContent("a" to GridCell(0, 0))))))
        assertSame(g, g.placeWidgetInCell(rootPath, "ghost", GridCell(1, 1), 4))
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
