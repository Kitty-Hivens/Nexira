package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutGraphFlatMapTest {

    private fun w(kind: String, id: String, children: Map<SlotId, SlotContent> = emptyMap()) =
        WidgetInstance(kind = WidgetKind(kind), instanceId = id, children = children)

    private fun graph(vararg widgets: WidgetInstance) = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(slots = mapOf(SlotId("a") to SlotContent(widgets.toList()))),
        ),
    )

    private fun LayoutGraph.slotA() = surfaces[SurfaceId("s")]!!.slots[SlotId("a")]!!.widgets

    @Test
    fun `identity transform returns an equal graph`() {
        val g = graph(w("x", "1"), w("y", "2"))
        assertEquals(g, g.flatMapInstances { listOf(it) })
    }

    @Test
    fun `mapping to empty drops the widget`() {
        val g = graph(w("drop", "1"), w("keep", "2"))
        val out = g.flatMapInstances { if (it.kind.value == "drop") emptyList() else listOf(it) }
        assertEquals(listOf("2"), out.slotA().map { it.instanceId })
    }

    @Test
    fun `one-to-many expansion produces distinct instances in place`() {
        val g = graph(w("solo", "0"), w("expand", "1"), w("solo", "2"))
        val out = g.flatMapInstances { wi ->
            if (wi.kind.value == "expand") listOf(w("e", "${wi.instanceId}-a"), w("e", "${wi.instanceId}-b"))
            else listOf(wi)
        }
        assertEquals(listOf("0", "1-a", "1-b", "2"), out.slotA().map { it.instanceId })
        assertEquals(listOf("solo", "e", "e", "solo"), out.slotA().map { it.kind.value })
    }

    @Test
    fun `transform recurses into nested children`() {
        val child = w("old", "c1")
        val container = w("container", "ctr", mapOf(SlotId("body") to SlotContent(listOf(child))))
        val out = graph(container).flatMapInstances {
            if (it.kind.value == "old") listOf(it.copy(kind = WidgetKind("new"))) else listOf(it)
        }
        val outChild = out.slotA().first().children[SlotId("body")]!!.widgets.first()
        assertEquals("new", outChild.kind.value)
        assertEquals("c1", outChild.instanceId)
    }

    @Test
    fun `untouched surfaces and slots are preserved`() {
        val g = LayoutGraph(surfaces = mapOf(
            SurfaceId("s1") to SurfaceLayout(slots = mapOf(SlotId("a") to SlotContent(listOf(w("hit", "1"))))),
            SurfaceId("s2") to SurfaceLayout(slots = mapOf(SlotId("b") to SlotContent(listOf(w("miss", "2"))))),
        ))
        val out = g.flatMapInstances {
            if (it.kind.value == "hit") listOf(it.copy(kind = WidgetKind("changed"))) else listOf(it)
        }
        assertEquals("changed", out.surfaces[SurfaceId("s1")]!!.slots[SlotId("a")]!!.widgets.first().kind.value)
        assertEquals("miss", out.surfaces[SurfaceId("s2")]!!.slots[SlotId("b")]!!.widgets.first().kind.value)
    }
}
