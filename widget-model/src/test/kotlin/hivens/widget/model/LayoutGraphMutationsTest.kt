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

    // ── Compat overloads still work ───────────────────────────────────

    @Test
    fun `compat overload of insertWidget on a flat SurfaceId-SlotId pair still works`() {
        val out = seed(w1).insertWidget(home, main, w2, 999)
        assertEquals(listOf(w1, w2), out.mainWidgets())
    }

    @Test
    fun `compat overload of moveWidget on a SlotAddress pair still works`() {
        val twoSlots = LayoutGraph(
            surfaces = mapOf(
                home to SurfaceLayout(slots = mapOf(
                    SlotId("top")    to SlotContent(listOf(w1)),
                    SlotId("bottom") to SlotContent(listOf(w2)),
                )),
            ),
        )
        val out = twoSlots.moveWidget(
            from       = SlotAddress(home, SlotId("top")),
            to         = SlotAddress(home, SlotId("bottom")),
            instanceId = "i1",
            toIndex    = 0,
        )
        assertEquals(listOf(w1, w2), out.surfaces[home]!!.slots[SlotId("bottom")]!!.widgets)
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
