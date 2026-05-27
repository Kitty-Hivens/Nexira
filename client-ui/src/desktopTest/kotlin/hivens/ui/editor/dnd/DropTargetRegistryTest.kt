package hivens.ui.editor.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import hivens.widget.model.NestedSegment
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DropTargetRegistryTest {

    private val homeNew = SurfaceId("home.new")
    private val main    = SlotId("main")
    private val slot    = SlotPath(homeNew, main)
    private val library = SurfaceId("library")
    private val body    = SlotId("body")
    private val otherSlot = SlotPath(library, body)

    private fun rect(top: Float, height: Float = 50f, left: Float = 0f, width: Float = 300f): Rect =
        Rect(left = left, top = top, right = left + width, bottom = top + height)

    @Test
    fun `insertionIndexInSlot picks the widget whose midpoint the pointer is above`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))     // [0, 50]
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 60f))    // [60, 110]
        r.registerWidget(slot, "c", index = 2, rect = rect(top = 120f))   // [120, 170]

        // Above midpoint of widget 0 -> index 0
        assertEquals(0, r.insertionIndexInSlot(slot, Offset(50f, 10f)))
        // Below midpoint of widget 0, above midpoint of widget 1 -> 1
        assertEquals(1, r.insertionIndexInSlot(slot, Offset(50f, 70f)))
        // Below all -> append at 3
        assertEquals(3, r.insertionIndexInSlot(slot, Offset(50f, 200f)))
    }

    @Test
    fun `insertionIndexInSlot returns zero for unknown slot`() {
        val r = DropTargetRegistry()
        assertEquals(0, r.insertionIndexInSlot(slot, Offset(0f, 0f)))
    }

    @Test
    fun `slotForPoint returns the slot whose widget rect contains the pointer`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot,      "a", index = 0, rect = rect(top = 0f))      // home.new at y=0..50
        r.registerWidget(otherSlot, "b", index = 0, rect = rect(top = 200f))    // library at y=200..250

        assertEquals(slot,      r.slotForPoint(Offset(50f, 25f)))
        assertEquals(otherSlot, r.slotForPoint(Offset(50f, 225f)))
    }

    @Test
    fun `slotForPoint falls back to vertical span with horizontal tolerance`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))    // [0, 50]
        r.registerWidget(slot, "b", index = 1, rect = rect(top = 60f))   // [60, 110]

        // Between the two widgets, inside the slot's vertical span +
        // tolerance and within X range -- should resolve.
        assertEquals(slot, r.slotForPoint(Offset(100f, 55f)))
    }

    @Test
    fun `slotForPoint returns null when pointer is outside every slot`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))

        // Way below the slot + tolerance -> null
        assertNull(r.slotForPoint(Offset(50f, 500f)))
        // Way right of the slot -> null
        assertNull(r.slotForPoint(Offset(900f, 25f)))
    }

    @Test
    fun `unregisterWidget removes from hit-test`() {
        val r = DropTargetRegistry()
        r.registerWidget(slot, "a", index = 0, rect = rect(top = 0f))
        r.unregisterWidget(slot, "a")
        // Slot still exists in map (empty); slotForPoint cannot match
        // because there are no widget rects to span.
        assertNull(r.slotForPoint(Offset(50f, 25f)))
    }

    // ── Nested slot hit-testing (smallest-area-first) ─────────────────

    private val containerPath: SlotPath = slot.child("container1", body)

    @Test
    fun `slotForPoint prefers the innermost (smallest-area) match when rects overlap`() {
        val r = DropTargetRegistry()
        // Root container occupies a large outer rect.
        r.registerWidget(slot, "container1", index = 0, rect = rect(top = 0f, height = 300f))
        // Inside it, a nested child sits in a tighter rect.
        r.registerWidget(containerPath, "inner", index = 0, rect = rect(top = 50f, height = 50f, left = 20f, width = 200f))

        // Pointer inside the inner child -- innermost (containerPath) wins.
        assertEquals(containerPath, r.slotForPoint(Offset(100f, 75f)))
        // Pointer inside the outer container only -- outer (slot) wins.
        assertEquals(slot, r.slotForPoint(Offset(280f, 200f)))
    }

    @Test
    fun `empty nested slot bounds resolve to the nested SlotPath, not the parent`() {
        val r = DropTargetRegistry()
        // Outer container has its own widget rect (the container chrome).
        r.registerWidget(slot, "container1", index = 0, rect = rect(top = 0f, height = 300f))
        // Empty body slot inside the container registers its placeholder bounds.
        r.registerSlot(containerPath, rect(top = 80f, height = 80f, left = 20f, width = 200f))

        // The placeholder bounds are strictly smaller than the container's
        // own widget rect -- nested path wins.
        assertEquals(containerPath, r.slotForPoint(Offset(100f, 120f)))
    }

    @Test
    fun `slotForPoint at depth 2 still picks innermost`() {
        val r = DropTargetRegistry()
        val deeper = containerPath.child("container2", SlotId("inner"))

        r.registerWidget(slot, "container1", index = 0, rect = rect(top = 0f, height = 400f))
        r.registerWidget(containerPath, "container2", index = 0, rect = rect(top = 50f, height = 300f, left = 10f, width = 280f))
        r.registerWidget(deeper, "leaf", index = 0, rect = rect(top = 100f, height = 50f, left = 30f, width = 220f))

        assertEquals(deeper, r.slotForPoint(Offset(140f, 120f)))
    }

    @Test
    fun `SlotPath equality distinguishes same-leaf-coords across containers`() {
        val r = DropTargetRegistry()
        val a = slot.child("c1", body)
        val b = slot.child("c2", body)

        r.registerWidget(a, "wA", index = 0, rect = rect(top = 0f))
        r.registerWidget(b, "wB", index = 0, rect = rect(top = 200f))

        assertEquals(a, r.slotForPoint(Offset(50f, 25f)))
        assertEquals(b, r.slotForPoint(Offset(50f, 225f)))
    }

    // Compile-time touch: NestedSegment is part of widget-model and we
    // expect tests to be able to build SlotPaths via either child()
    // chains or direct construction.
    @Suppress("unused")
    private fun touchNestedSegment(): NestedSegment = NestedSegment("x", body)
}
