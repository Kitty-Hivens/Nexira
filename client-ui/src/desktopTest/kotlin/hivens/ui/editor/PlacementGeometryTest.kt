package hivens.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Pins the canvas placement + lattice math (offset / clamp / resize / window->local
// / cell move / span resize) so the gesture code that wraps it stays an untested thin
// shell.
class PlacementGeometryTest {

    private val eps = 0.001f

    @Test
    fun `windowPointToSlotDp subtracts the slot origin and divides by density`() {
        val (x, y) = windowPointToSlotDp(165f, 100f, slotOriginXPx = 65f, slotOriginYPx = 40f, density = 2f)
        assertEquals(50f, x, eps)
        assertEquals(30f, y, eps)
    }

    @Test
    fun `placementDragOffset adds the dp delta to the start on a roomy slot`() {
        val (x, y) = placementDragOffset(
            startXDp = 10f, startYDp = 10f,
            accumXPx = 40f, accumYPx = 20f,
            density = 2f,
            slotWDp = 800f, slotHDp = 600f,
            widgetWDp = 100f, widgetHDp = 50f,
            hBias = 0f, vBias = 0f,
        )
        assertEquals(30f, x, eps)
        assertEquals(20f, y, eps)
    }

    @Test
    fun `clampPlacementOffset keeps a grab margin inside each edge`() {
        val (x, y) = clampPlacementOffset(
            xDp = 10_000f, yDp = -10_000f,
            slotWDp = 800f, slotHDp = 600f,
            widgetWDp = 100f, widgetHDp = 50f,
            hBias = 0f, vBias = 0f,
            grabMarginDp = 24f,
        )
        assertEquals(776f, x, eps)   // slotW - margin
        assertEquals(-26f, y, eps)   // margin - widgetH
    }

    @Test
    fun `clampPlacementOffset passes through when the slot is unmeasured`() {
        val (x, y) = clampPlacementOffset(1234f, -99f, slotWDp = 0f, slotHDp = 0f, widgetWDp = 100f, widgetHDp = 50f, hBias = 0f, vBias = 0f, grabMarginDp = 24f)
        assertEquals(1234f, x, eps)
        assertEquals(-99f, y, eps)
    }

    @Test
    fun `clampPlacementOffset passes through when the margins exceed the slot`() {
        // slot 10dp, widget 5dp, margin 24 -> lo=19 > hi=-14, inverted: no clamp.
        val (x, y) = clampPlacementOffset(7f, 7f, slotWDp = 10f, slotHDp = 10f, widgetWDp = 5f, widgetHDp = 5f, hBias = 0f, vBias = 0f, grabMarginDp = 24f)
        assertEquals(7f, x, eps)
        assertEquals(7f, y, eps)
    }

    @Test
    fun `canvasResizeSize grows by the dp delta and clamps to the minimum`() {
        val (w, h) = canvasResizeSize(200f, 120f, accumXPx = -400f, accumYPx = -400f, density = 2f, minDp = 48f)
        assertEquals(48f, w, eps)
        assertEquals(48f, h, eps)
    }

    // Cube grid: stride = cell(100) + gutter(10) = 110dp; at density 2 that is 220px per cell.

    @Test
    fun `gridDragCell shifts the start cell by whole cells`() {
        val (col, row) = gridDragCell(startCol = 1, startRow = 1, accumXPx = 220f, accumYPx = 440f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(2, col)
        assertEquals(3, row)
    }

    @Test
    fun `gridDragCell clamps the column inside the grid`() {
        val (col, _) = gridDragCell(startCol = 3, startRow = 0, accumXPx = 2200f, accumYPx = 0f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(3, col)
    }

    @Test
    fun `gridDragCell floors the row at zero`() {
        val (_, row) = gridDragCell(startCol = 1, startRow = 0, accumXPx = 0f, accumYPx = -2200f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(0, row)
    }

    @Test
    fun `gridDragCell returns the start cell for a degenerate stride`() {
        val (col, row) = gridDragCell(startCol = 2, startRow = 2, accumXPx = 500f, accumYPx = 500f, density = 2f, cellWidthDp = 0f, gutterDp = 0f, columns = 4)
        assertEquals(2, col)
        assertEquals(2, row)
    }

    @Test
    fun `gridResizeSpan grows the span by whole cells`() {
        val (cs, rs) = gridResizeSpan(startColSpan = 1, startRowSpan = 1, accumXPx = 220f, accumYPx = 220f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(2, cs)
        assertEquals(2, rs)
    }

    @Test
    fun `gridResizeSpan floors each span at one`() {
        val (cs, rs) = gridResizeSpan(startColSpan = 2, startRowSpan = 2, accumXPx = -2200f, accumYPx = -2200f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(1, cs)
        assertEquals(1, rs)
    }

    @Test
    fun `gridResizeSpan caps the column span at the grid width`() {
        val (cs, _) = gridResizeSpan(startColSpan = 1, startRowSpan = 1, accumXPx = 2200f, accumYPx = 0f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(4, cs)
    }

    // ── Resizing from any edge ───────────────────────────────────────

    /**
     * Dragging a leading edge moves the origin as well as the size, and which way
     * the origin moves depends on the anchor the offset counts from. These pin all
     * three readings, because getting the sign wrong walks the widget sideways
     * under the pointer and looks like the handle is broken rather than the maths.
     */
    private fun resize(
        edge: ResizeEdge,
        x: Float = 100f,
        y: Float = 100f,
        w: Float = 200f,
        h: Float = 200f,
        dx: Float = 0f,
        dy: Float = 0f,
        hBias: Float = 0f,
        vBias: Float = 0f,
        slot: Float = 1000f,
    ) = canvasResize(
        edge, x, y, w, h, dx, dy, density = 1f,
        slotWDp = slot, slotHDp = slot, hBias = hBias, vBias = vBias,
    )

    @Test
    fun `a trailing edge grows the size and leaves the origin alone`() {
        val r = resize(ResizeEdge.SouthEast, dx = 60f, dy = 40f)
        assertEquals(100f, r.x, eps)
        assertEquals(100f, r.y, eps)
        assertEquals(260f, r.w, eps)
        assertEquals(240f, r.h, eps)
    }

    @Test
    fun `a leading edge moves the origin by the same amount it shrinks`() {
        val r = resize(ResizeEdge.NorthWest, dx = 30f, dy = 50f)
        assertEquals(130f, r.x, eps)
        assertEquals(150f, r.y, eps)
        assertEquals(170f, r.w, eps)
        assertEquals(150f, r.h, eps)
    }

    @Test
    fun `a side handle leaves the other axis untouched`() {
        val r = resize(ResizeEdge.East, dx = 40f, dy = 999f)
        assertEquals(240f, r.w, eps)
        assertEquals(200f, r.h, eps, "a vertical delta on an east handle is not a resize")
        assertEquals(100f, r.y, eps)
    }

    @Test
    fun `against an end anchor the roles invert`() {
        // The offset is an inset from the right, so the right edge is the pinned
        // one: pulling the LEFT edge changes only the width.
        val west = resize(ResizeEdge.West, dx = 30f, hBias = 1f)
        assertEquals(100f, west.x, eps)
        assertEquals(170f, west.w, eps)

        // And pulling the right edge is what moves the stored number, downward,
        // because the inset shrinks as the edge travels away from its own side.
        val east = resize(ResizeEdge.East, dx = 30f, hBias = 1f)
        assertEquals(70f, east.x, eps)
        assertEquals(230f, east.w, eps)
    }

    @Test
    fun `against a centre anchor the origin takes half the delta`() {
        val r = resize(ResizeEdge.East, x = 0f, dx = 40f, hBias = 0.5f)
        assertEquals(20f, r.x, eps, "growing one side moves the centre by half of it")
        assertEquals(240f, r.w, eps)
    }

    @Test
    fun `the minimum holds the dragged edge and does not drag the other one along`() {
        // Pushing the left edge far past the right stops at the floor, and the
        // right edge has to stay where it was rather than being carried with it.
        val r = resize(ResizeEdge.West, dx = 400f)
        assertEquals(MIN_WIDGET_DP, r.w, eps)
        assertEquals(300f - MIN_WIDGET_DP, r.x, eps, "the untouched edge stays at 300")
    }

    // ── What the widget says it can be ───────────────────────────────

    @Test
    fun `an undeclared widget keeps the editor's own floor and no ceiling`() {
        val open = ResizeBounds.OPEN
        assertEquals(MIN_WIDGET_DP, open.hold(1f), eps)
        assertEquals(9999f, open.hold(9999f), eps)
    }

    @Test
    fun `a declared range replaces the fallback on the axis that declares it`() {
        val b = ResizeBounds.of(declaredMin = 88, declaredMax = 320)
        assertEquals(88f, b.hold(10f), eps)
        assertEquals(320f, b.hold(9999f), eps)
        assertEquals(148f, b.hold(148f), eps)
    }

    @Test
    fun `no ceiling is distinguishable from a very large one`() {
        // The range guide asks isFinite to decide whether to draw a line, and
        // Float.MAX_VALUE answers yes, so it drew a rectangle the size of the
        // number instead of drawing nothing.
        assertFalse(ResizeBounds.OPEN.maxDp.isFinite())
        assertFalse(ResizeBounds.of(declaredMin = 88, declaredMax = 0).maxDp.isFinite())
        assertTrue(ResizeBounds.of(declaredMin = 88, declaredMax = 320).maxDp.isFinite())
    }

    @Test
    fun `an axis that declares only a ceiling keeps the fallback floor`() {
        val b = ResizeBounds.of(declaredMin = 0, declaredMax = 320)
        assertEquals(MIN_WIDGET_DP, b.hold(1f), eps)
        assertEquals(320f, b.hold(9999f), eps)
    }

    @Test
    fun `a resize stops at the declared ceiling instead of writing past it`() {
        // The stored size, the editor's frame and the pixels were three answers
        // once a handle was dragged past where the widget stops drawing.
        val r = canvasResize(
            ResizeEdge.SouthEast, 100f, 100f, 200f, 200f, 9999f, 9999f, density = 1f,
            slotWDp = 4000f, slotHDp = 4000f, hBias = 0f, vBias = 0f,
            widthBounds = ResizeBounds.of(88, 320),
            heightBounds = ResizeBounds.of(88, 240),
        )
        assertEquals(320f, r.w, eps)
        assertEquals(240f, r.h, eps)
    }

    @Test
    fun `a resize stops at the declared floor rather than the editor's`() {
        val r = canvasResize(
            ResizeEdge.SouthEast, 100f, 100f, 200f, 200f, -9999f, -9999f, density = 1f,
            slotWDp = 1000f, slotHDp = 1000f, hBias = 0f, vBias = 0f,
            widthBounds = ResizeBounds.of(88, 320),
            heightBounds = ResizeBounds.of(88, 320),
        )
        assertEquals(88f, r.w, eps, "the widget's own floor, not the editor's 48")
        assertEquals(88f, r.h, eps)
    }

    @Test
    fun `the ceiling holds the dragged edge and leaves the other one alone`() {
        // Same rule the floor follows: bottoming out on one edge must not carry
        // the opposite edge along with it.
        val r = canvasResize(
            ResizeEdge.West, 100f, 100f, 200f, 200f, -9999f, 0f, density = 1f,
            slotWDp = 4000f, slotHDp = 1000f, hBias = 0f, vBias = 0f,
            widthBounds = ResizeBounds.of(0, 320),
        )
        assertEquals(320f, r.w, eps)
        assertEquals(300f - 320f, r.x, eps, "the untouched right edge stays at 300")
    }

    // ── Where the range is drawn ─────────────────────────────────────

    @Test
    fun `a guide hangs off the corner the resize pivots on`() {
        // Start anchor: the leading edge is pinned, so the guide starts there.
        assertEquals(0f, guideLeadDp(ownDp = 200f, extentDp = 88f, bias = 0f), eps)
        // End anchor: the trailing edge is pinned, so the guide ends there.
        assertEquals(112f, guideLeadDp(ownDp = 200f, extentDp = 88f, bias = 1f), eps)
        // Centre: split.
        assertEquals(56f, guideLeadDp(ownDp = 200f, extentDp = 88f, bias = 0.5f), eps)
    }

    @Test
    fun `a guide larger than the widget hangs outward rather than being clipped in`() {
        assertEquals(-120f, guideLeadDp(ownDp = 200f, extentDp = 320f, bias = 1f), eps)
        assertEquals(0f, guideLeadDp(ownDp = 200f, extentDp = 320f, bias = 0f), eps)
    }

    @Test
    fun `a corner resize round-trips back to where it started`() {
        val out = resize(ResizeEdge.NorthWest, dx = 35f, dy = 35f)
        val back = canvasResize(
            ResizeEdge.NorthWest, out.x, out.y, out.w, out.h, -35f, -35f, density = 1f,
            slotWDp = 1000f, slotHDp = 1000f, hBias = 0f, vBias = 0f,
        )
        assertEquals(100f, back.x, eps)
        assertEquals(100f, back.y, eps)
        assertEquals(200f, back.w, eps)
        assertEquals(200f, back.h, eps)
    }
}
