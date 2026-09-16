package hivens.ui.editor

import hivens.widget.model.clampPlacementAxis
import kotlin.math.roundToInt

// Pure placement geometry, for both units a slot can measure in: dp on a free
// slot, whole cells on a lattice. Compose-free (plain Floats) so the math is
// unit-testable without a real pointer or density. The gesture code supplies px
// from Compose and the dp scale from LocalDensity; everything reducible to
// arithmetic lives here. All sizes are dp unless the name says Px or the
// function says cell.

// Slot-local dp for a palette drop: window pointer minus the slot's window
// origin, px -> dp.
internal fun windowPointToSlotDp(
    pointerXPx: Float,
    pointerYPx: Float,
    slotOriginXPx: Float,
    slotOriginYPx: Float,
    density: Float,
): Pair<Float, Float> =
    (pointerXPx - slotOriginXPx) / density to (pointerYPx - slotOriginYPx) / density

// New offset for a placed widget being dragged: the placement it started the
// drag at, plus the accumulated pointer delta (px -> dp), clamped so a grab
// margin always stays inside the slot.
internal fun placementDragOffset(
    startXDp: Float,
    startYDp: Float,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    slotWDp: Float,
    slotHDp: Float,
    widgetWDp: Float,
    widgetHDp: Float,
    hBias: Float,
    vBias: Float,
    grabMarginDp: Float = 24f,
): Pair<Float, Float> =
    clampPlacementOffset(
        startXDp + accumXPx / density,
        startYDp + accumYPx / density,
        slotWDp, slotHDp, widgetWDp, widgetHDp, hBias, vBias, grabMarginDp,
    )

/**
 * Clamp so a widget cannot be dragged out of reach: at least [grabMarginDp]
 * stays inside on every edge.
 *
 * The bias is what makes this more than a coerce. An offset is measured from the
 * anchor, so the same number means a different place depending on which corner it
 * counts from, and a clamp written for the top left lets a centred one travel a
 * whole slot width away before it notices: a widget anchored to the bottom centre
 * and dragged right ended up a thousand dp past the edge with nothing left to grab.
 *
 * A degenerate (unmeasured) slot, or one too small to hold the margins, disables
 * clamping on that axis rather than pinning to 0 or throwing on an inverted range.
 */
internal fun clampPlacementOffset(
    xDp: Float,
    yDp: Float,
    slotWDp: Float,
    slotHDp: Float,
    widgetWDp: Float,
    widgetHDp: Float,
    hBias: Float,
    vBias: Float,
    grabMarginDp: Float,
): Pair<Float, Float> =
    clampAxis(xDp, slotWDp, widgetWDp, hBias, grabMarginDp) to
        clampAxis(yDp, slotHDp, widgetHDp, vBias, grabMarginDp)

// The renderer clamps what it draws from the same function, so a drag cannot
// write a position the drawing would then refuse to put under the pointer.
private fun clampAxis(vDp: Float, slotDp: Float, widgetDp: Float, bias: Float, marginDp: Float): Float =
    clampPlacementAxis(vDp, slotDp, widgetDp, bias, marginDp)

// New size for a corner-resize drag: start size plus accumulated px delta
// (px -> dp), clamped to a minimum so a widget can't collapse to nothing.
internal fun canvasResizeSize(
    startWDp: Float,
    startHDp: Float,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    minDp: Float = 48f,
): Pair<Float, Float> =
    (startWDp + accumXPx / density).coerceAtLeast(minDp) to
        (startHDp + accumYPx / density).coerceAtLeast(minDp)

// Target cell for a lattice MOVE drag: the widget's start cell shifted by the
// accumulated pointer delta rounded to whole cells (stride = cell width + gutter).
// Column clamps inside the grid; row only floors at 0 (the grid grows downward).
internal fun gridDragCell(
    startCol: Int,
    startRow: Int,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    cellWidthDp: Float,
    gutterDp: Float,
    columns: Int,
): Pair<Int, Int> {
    val stride = cellWidthDp + gutterDp
    if (stride <= 0f || density <= 0f) return startCol to startRow
    val col = (startCol + ((accumXPx / density) / stride).roundToInt()).coerceIn(0, (columns - 1).coerceAtLeast(0))
    val row = (startRow + ((accumYPx / density) / stride).roundToInt()).coerceAtLeast(0)
    return col to row
}

// Target span for a lattice RESIZE drag: the widget's start span grown by the
// accumulated pointer delta rounded to whole cells. Each span floors at 1; the
// column span is capped at the grid width (a widget can be at most `columns` wide).
internal fun gridResizeSpan(
    startColSpan: Int,
    startRowSpan: Int,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    cellWidthDp: Float,
    gutterDp: Float,
    columns: Int,
): Pair<Int, Int> {
    val stride = cellWidthDp + gutterDp
    if (stride <= 0f || density <= 0f) return startColSpan to startRowSpan
    val colSpan = (startColSpan + ((accumXPx / density) / stride).roundToInt()).coerceIn(1, columns.coerceAtLeast(1))
    val rowSpan = (startRowSpan + ((accumYPx / density) / stride).roundToInt()).coerceAtLeast(1)
    return colSpan to rowSpan
}
