package hivens.ui.editor.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DropTargetRegistryTest {

    private val launcher = CompositionName("launcher")
    private val body = SlotId("body")
    private val home = NodePath(launcher, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
    private val slot = home.child("centre", body)
    private val otherSlot = NodePath(launcher, NodeRoot.Layout(ConfigId("library"), LayoutId("main"))).child("centre", body)

    private fun rect(top: Float, height: Float = 50f, left: Float = 0f, width: Float = 300f): Rect =
        Rect(left = left, top = top, right = left + width, bottom = top + height)

    /**
     * A slot from a page that has gone is smaller than the live pane under it, and the smallest rectangle wins, so kept
     * it took the drop.
     */
    @Test
    fun `a withdrawn slot no longer takes a drop meant for the live slot beneath`() {
        val r = DropTargetRegistry()
        r.registerSlot(otherSlot, rect(top = 0f, height = 400f))
        r.registerSlot(slot, rect(top = 100f, height = 80f))
        assertEquals(slot, r.slotForPoint(Offset(50f, 120f)))

        r.withdrawSlot(slot)

        assertEquals(otherSlot, r.slotForPoint(Offset(50f, 120f)))
        assertNull(r.slotRect(slot))
    }

    @Test
    fun `the whole of a scrolling slot is kept apart from what is on screen, and goes with it`() {
        val r = DropTargetRegistry()
        r.registerSlot(slot, rect(top = 0f, height = 300f))
        r.registerSlotContent(slot, rect(top = -600f, height = 1200f))
        assertEquals(rect(top = -600f, height = 1200f), r.slotContentRect(slot))
        assertEquals(rect(top = 0f, height = 300f), r.slotRect(slot), "the pointer is over what is on screen")

        r.registerSlotContent(slot, rect(top = 0f, height = 300f))
        assertEquals(rect(top = 0f, height = 300f), r.slotContentRect(slot), "the same rect is not kept twice")

        r.registerSlotContent(slot, rect(top = -600f, height = 1200f))
        r.withdrawSlot(slot)
        assertNull(r.slotContentRect(slot))
    }

    @Test
    fun `insertionIndexInSlot picks the widget whose midpoint the pointer is above`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))     // [0, 50]
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 60f))    // [60, 110]
        r.registerWidget(slot, "c", index = 2, rect = rect(top = 120f))   // [120, 170]

        assertEquals(0, r.insertionIndexInSlot(slot, Offset(50f, 10f), FlowSpec.Column))
        assertEquals(1, r.insertionIndexInSlot(slot, Offset(50f, 70f), FlowSpec.Column))
        assertEquals(3, r.insertionIndexInSlot(slot, Offset(50f, 200f), FlowSpec.Column), "below all appends")
    }

    @Test
    fun `insertionIndexInSlot uses the X axis for a Row slot`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f, height = 80f, left = 0f, width = 100f))
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 0f, height = 80f, left = 110f, width = 100f))
        r.registerWidget(slot, "c", index = 2, rect = rect(top = 0f, height = 80f, left = 220f, width = 100f))

        val row = FlowSpec.Row
        assertEquals(0, r.insertionIndexInSlot(slot, Offset(10f, 40f), row))
        assertEquals(1, r.insertionIndexInSlot(slot, Offset(120f, 40f), row))
        assertEquals(3, r.insertionIndexInSlot(slot, Offset(400f, 40f), row))
        assertEquals(1, r.insertionIndexInSlot(slot, Offset(120f, 999f), row), "Y is ignored for a row")
    }

    @Test
    fun `insertionIndexInSlot is row-major for a Grid slot`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f, height = 80f, left = 0f, width = 100f))
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 0f, height = 80f, left = 110f, width = 100f))
        r.registerWidget(slot, "c", index = 2, rect = rect(top = 100f, height = 80f, left = 0f, width = 100f))
        r.registerWidget(slot, "d", index = 3, rect = rect(top = 100f, height = 80f, left = 110f, width = 100f))

        val grid = FlowSpec.grid(2)
        assertEquals(0, r.insertionIndexInSlot(slot, Offset(50f, -10f), grid))
        assertEquals(0, r.insertionIndexInSlot(slot, Offset(10f, 40f), grid))
        assertEquals(1, r.insertionIndexInSlot(slot, Offset(120f, 40f), grid))
        assertEquals(2, r.insertionIndexInSlot(slot, Offset(10f, 140f), grid))
        assertEquals(4, r.insertionIndexInSlot(slot, Offset(400f, 400f), grid))
    }

    @Test
    fun `a placement slot appends, and an unknown slot answers zero`() {
        val r = DropTargetRegistry()
        assertEquals(0, r.insertionIndexInSlot(slot, Offset(0f, 0f), FlowSpec.Column))
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 60f))
        assertEquals(2, r.insertionIndexInSlot(slot, Offset(0f, 0f), null))
    }

    @Test
    fun `the slot under the pointer is answered from the slots, a gap between widgets included`() {
        val r = DropTargetRegistry()
        r.registerSlot(slot, rect(top = 0f, height = 120f))
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 60f))

        assertEquals(slot, r.slotForPoint(Offset(100f, 55f)))
        assertNull(r.slotForPoint(Offset(50f, 500f)))
        assertNull(r.slotForPoint(Offset(900f, 25f)))
    }

    @Test
    fun `a widget rect is never a slot`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))
        assertNull(r.slotForPoint(Offset(50f, 25f)), "a slot that reported no rect is not under the pointer")
    }

    private val containerPath: NodePath = slot.child("container1", body)

    @Test
    fun `the innermost slot wins where slots overlap, at any depth`() {
        val r = DropTargetRegistry()
        val deeper = containerPath.child("container2", SlotId("inner"))
        r.registerSlot(slot, rect(top = 0f, height = 400f))
        r.registerSlot(containerPath, rect(top = 50f, height = 300f, left = 10f, width = 280f))
        r.registerSlot(deeper, rect(top = 100f, height = 50f, left = 30f, width = 220f))

        assertEquals(deeper, r.slotForPoint(Offset(140f, 120f)))
        assertEquals(containerPath, r.slotForPoint(Offset(140f, 300f)))
        assertEquals(slot, r.slotForPoint(Offset(295f, 380f)))
    }

    @Test
    fun `two slots of one size tie to the deeper path`() {
        val r = DropTargetRegistry()
        r.registerSlot(containerPath, rect(top = 0f, height = 100f))
        r.registerSlot(slot, rect(top = 0f, height = 100f))
        assertEquals(containerPath, r.slotForPoint(Offset(10f, 10f)))
    }

    @Test
    fun `the same slot of two containers is two targets`() {
        val r = DropTargetRegistry()
        val a = slot.child("c1", body)
        val b = slot.child("c2", body)
        r.registerSlot(a, rect(top = 0f))
        r.registerSlot(b, rect(top = 200f))

        assertEquals(a, r.slotForPoint(Offset(50f, 25f)))
        assertEquals(b, r.slotForPoint(Offset(50f, 225f)))
    }

    @Test
    fun `the widget under the pointer is the innermost, and an unregistered one is gone`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "container1", index = 0, rect = rect(top = 0f, height = 300f))
        r.registerWidget(containerPath, "inner", index = 0, rect = rect(top = 50f, height = 50f, left = 20f, width = 200f))

        assertEquals(WidgetKey(containerPath, "inner"), r.widgetForPoint(Offset(100f, 75f)))
        assertEquals(WidgetKey(slot, "container1"), r.widgetForPoint(Offset(280f, 200f)))

        r.unregisterWidget(containerPath, "inner")
        assertEquals(WidgetKey(slot, "container1"), r.widgetForPoint(Offset(100f, 75f)))
    }

    @Test
    fun `a moving widget is told who it lands on, flush edges left out`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f, height = 100f))
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 100f, height = 100f))
        r.registerWidget(slot, "moving", index = 2, rect = rect(top = 150f, height = 100f))

        assertEquals(setOf(WidgetKey(slot, "b")), r.overlapping(slot, rect(top = 150f, height = 100f), "moving"))
        assertEquals(emptySet(), r.overlapping(slot, rect(top = 200f, height = 100f), "moving"))
    }
}
