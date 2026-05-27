package hivens.widget.model

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `insertWidget at zero pushes existing widgets back`() {
        val out = seed(w1, w2).insertWidget(home, main, w3, 0)
        assertEquals(listOf(w3, w1, w2), out.mainWidgets())
    }

    @Test
    fun `insertWidget past end coerces to append`() {
        val out = seed(w1).insertWidget(home, main, w2, 999)
        assertEquals(listOf(w1, w2), out.mainWidgets())
    }

    @Test
    fun `insertWidget into unknown slot is a no-op identity return`() {
        val graph = seed(w1)
        val out = graph.insertWidget(home, SlotId("nope"), w2, 0)
        assertSame(graph, out, "no-op must return the same instance (no allocation)")
    }

    @Test
    fun `removeWidget filters by instanceId`() {
        val out = seed(w1, w2, w3).removeWidget(home, main, "i2")
        assertEquals(listOf(w1, w3), out.mainWidgets())
    }

    @Test
    fun `removeWidget of unknown id is identity`() {
        val graph = seed(w1)
        assertSame(graph, graph.removeWidget(home, main, "ghost"))
    }

    @Test
    fun `reorderInSlot swaps positions`() {
        val out = seed(w1, w2, w3).reorderInSlot(home, main, fromIndex = 0, toIndex = 2)
        assertEquals(listOf(w2, w3, w1), out.mainWidgets())
    }

    @Test
    fun `reorderInSlot with same index is identity`() {
        val graph = seed(w1, w2)
        assertSame(graph, graph.reorderInSlot(home, main, fromIndex = 0, toIndex = 0))
    }

    @Test
    fun `reorderInSlot out-of-range fromIndex is identity`() {
        val graph = seed(w1, w2)
        assertSame(graph, graph.reorderInSlot(home, main, fromIndex = 5, toIndex = 0))
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
            from       = SlotAddress(home, SlotId("top")),
            to         = SlotAddress(home, SlotId("bottom")),
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
            from       = SlotAddress(home, main),
            to         = SlotAddress(home, main),
            instanceId = "i3",
            toIndex    = 0,
        )
        assertEquals(listOf(w3, w1, w2), out.mainWidgets())
    }

    @Test
    fun `moveWidget of unknown instanceId is identity`() {
        val graph = seed(w1)
        val out = graph.moveWidget(
            from       = SlotAddress(home, main),
            to         = SlotAddress(home, main),
            instanceId = "ghost",
            toIndex    = 0,
        )
        assertSame(graph, out)
    }
}
