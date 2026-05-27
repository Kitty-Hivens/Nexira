package hivens.ui.editor.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DropTargetRegistryTest {

    private val homeNew = SurfaceId("home.new")
    private val main    = SlotId("main")
    private val slot    = SlotAddress(homeNew, main)
    private val library = SurfaceId("library")
    private val body    = SlotId("body")
    private val otherSlot = SlotAddress(library, body)

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
}
