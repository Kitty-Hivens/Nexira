package hivens.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

// Pins the canvas placement + cube-grid math (offset / clamp / resize / window->local
// / cell move / span resize) so the gesture code that wraps it stays an untested thin
// shell.
class CanvasGeometryTest {

    private val eps = 0.001f

    @Test
    fun `windowPointToSlotDp subtracts the slot origin and divides by density`() {
        val (x, y) = windowPointToSlotDp(165f, 100f, slotOriginXPx = 65f, slotOriginYPx = 40f, density = 2f)
        assertEquals(50f, x, eps)
        assertEquals(30f, y, eps)
    }

    @Test
    fun `canvasDragOffset adds the dp delta to the start on a roomy slot`() {
        val (x, y) = canvasDragOffset(
            startXDp = 10f, startYDp = 10f,
            accumXPx = 40f, accumYPx = 20f,
            density = 2f,
            slotWDp = 800f, slotHDp = 600f,
            widgetWDp = 100f, widgetHDp = 50f,
        )
        assertEquals(30f, x, eps)
        assertEquals(20f, y, eps)
    }

    @Test
    fun `clampCanvasOffset keeps a grab margin inside each edge`() {
        val (x, y) = clampCanvasOffset(
            xDp = 10_000f, yDp = -10_000f,
            slotWDp = 800f, slotHDp = 600f,
            widgetWDp = 100f, widgetHDp = 50f,
            grabMarginDp = 24f,
        )
        assertEquals(776f, x, eps)   // slotW - margin
        assertEquals(-26f, y, eps)   // margin - widgetH
    }

    @Test
    fun `clampCanvasOffset passes through when the slot is unmeasured`() {
        val (x, y) = clampCanvasOffset(1234f, -99f, slotWDp = 0f, slotHDp = 0f, widgetWDp = 100f, widgetHDp = 50f, grabMarginDp = 24f)
        assertEquals(1234f, x, eps)
        assertEquals(-99f, y, eps)
    }

    @Test
    fun `clampCanvasOffset passes through when the margins exceed the slot`() {
        // slot 10dp, widget 5dp, margin 24 -> lo=19 > hi=-14, inverted: no clamp.
        val (x, y) = clampCanvasOffset(7f, 7f, slotWDp = 10f, slotHDp = 10f, widgetWDp = 5f, widgetHDp = 5f, grabMarginDp = 24f)
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
    fun `cubeDragCell shifts the start cell by whole cells`() {
        val (col, row) = cubeDragCell(startCol = 1, startRow = 1, accumXPx = 220f, accumYPx = 440f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(2, col)
        assertEquals(3, row)
    }

    @Test
    fun `cubeDragCell clamps the column inside the grid`() {
        val (col, _) = cubeDragCell(startCol = 3, startRow = 0, accumXPx = 2200f, accumYPx = 0f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(3, col)
    }

    @Test
    fun `cubeDragCell floors the row at zero`() {
        val (_, row) = cubeDragCell(startCol = 1, startRow = 0, accumXPx = 0f, accumYPx = -2200f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(0, row)
    }

    @Test
    fun `cubeDragCell returns the start cell for a degenerate stride`() {
        val (col, row) = cubeDragCell(startCol = 2, startRow = 2, accumXPx = 500f, accumYPx = 500f, density = 2f, cellWidthDp = 0f, gutterDp = 0f, columns = 4)
        assertEquals(2, col)
        assertEquals(2, row)
    }

    @Test
    fun `cubeResizeSpan grows the span by whole cells`() {
        val (cs, rs) = cubeResizeSpan(startColSpan = 1, startRowSpan = 1, accumXPx = 220f, accumYPx = 220f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(2, cs)
        assertEquals(2, rs)
    }

    @Test
    fun `cubeResizeSpan floors each span at one`() {
        val (cs, rs) = cubeResizeSpan(startColSpan = 2, startRowSpan = 2, accumXPx = -2200f, accumYPx = -2200f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(1, cs)
        assertEquals(1, rs)
    }

    @Test
    fun `cubeResizeSpan caps the column span at the grid width`() {
        val (cs, _) = cubeResizeSpan(startColSpan = 1, startRowSpan = 1, accumXPx = 2200f, accumYPx = 0f, density = 2f, cellWidthDp = 100f, gutterDp = 10f, columns = 4)
        assertEquals(4, cs)
    }
}
