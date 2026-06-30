package hivens.ui.editor

import kotlin.math.roundToInt

// Pure canvas free-placement geometry. Compose-free (plain Floats) so the math
// is unit-testable without a real pointer or density -- the same discipline as
// dividerLeftWeight. The gesture code supplies px from Compose and the dp scale
// from LocalDensity; everything reducible to arithmetic lives here. All sizes
// are dp unless the name says Px.

// Slot-local dp for a palette drop: window pointer minus the canvas slot's
// window origin, px -> dp.
internal fun windowPointToSlotDp(
    pointerXPx: Float,
    pointerYPx: Float,
    slotOriginXPx: Float,
    slotOriginYPx: Float,
    density: Float,
): Pair<Float, Float> =
    (pointerXPx - slotOriginXPx) / density to (pointerYPx - slotOriginYPx) / density

// New offset for a canvas widget being dragged: the placement it started the
// drag at, plus the accumulated pointer delta (px -> dp), clamped so a grab
// margin always stays on-canvas.
internal fun canvasDragOffset(
    startXDp: Float,
    startYDp: Float,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    slotWDp: Float,
    slotHDp: Float,
    widgetWDp: Float,
    widgetHDp: Float,
    grabMarginDp: Float = 24f,
): Pair<Float, Float> =
    clampCanvasOffset(
        startXDp + accumXPx / density,
        startYDp + accumYPx / density,
        slotWDp, slotHDp, widgetWDp, widgetHDp, grabMarginDp,
    )

// Clamp so a widget can't be dragged fully off-canvas: at least grabMargin dp
// stays inside on every edge. A degenerate (unmeasured) slot, or a slot too
// small to hold the margins, disables clamping on that axis rather than
// pinning to 0 or throwing on an inverted range.
internal fun clampCanvasOffset(
    xDp: Float,
    yDp: Float,
    slotWDp: Float,
    slotHDp: Float,
    widgetWDp: Float,
    widgetHDp: Float,
    grabMarginDp: Float,
): Pair<Float, Float> =
    clampAxis(xDp, slotWDp, widgetWDp, grabMarginDp) to
        clampAxis(yDp, slotHDp, widgetHDp, grabMarginDp)

private fun clampAxis(vDp: Float, slotDp: Float, widgetDp: Float, marginDp: Float): Float {
    if (slotDp <= 0f) return vDp
    val lo = marginDp - widgetDp
    val hi = slotDp - marginDp
    return if (lo > hi) vDp else vDp.coerceIn(lo, hi)
}

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

// Target cell for a cube-grid MOVE drag: the widget's start cell shifted by the
// accumulated pointer delta rounded to whole cells (stride = cell width + gutter).
// Column clamps inside the grid; row only floors at 0 (the grid grows downward).
internal fun cubeDragCell(
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

// Target span for a cube-grid RMB-RESIZE drag: the widget's start span grown by the
// accumulated pointer delta rounded to whole cells. Each span floors at 1; the
// column span is capped at the grid width (a widget can be at most `columns` wide).
internal fun cubeResizeSpan(
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
