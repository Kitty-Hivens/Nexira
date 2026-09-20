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
    minDp: Float = MIN_WIDGET_DP,
): Pair<Float, Float> =
    (startWDp + accumXPx / density).coerceAtLeast(minDp) to
        (startHDp + accumYPx / density).coerceAtLeast(minDp)

/** The smallest a widget may be dragged to, so it cannot collapse out of reach. */
internal const val MIN_WIDGET_DP = 48f

/**
 * Which edges of a widget a handle moves.
 *
 * [h] and [v] say which edge on each axis: -1 the leading one (left, top), +1 the
 * trailing one (right, bottom), 0 neither. A corner moves one of each, a side one
 * and nothing on the other axis.
 */
internal enum class ResizeEdge(val h: Int, val v: Int) {
    North(0, -1),
    South(0, 1),
    West(-1, 0),
    East(1, 0),
    NorthWest(-1, -1),
    NorthEast(1, -1),
    SouthWest(-1, 1),
    SouthEast(1, 1),
}

/** Where a resize drag leaves a widget: an offset in the slot's unit, and a size. */
internal data class ResizedPlacement(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Where [edge] dragged by an accumulated pointer delta leaves the widget.
 *
 * Dragging a leading edge moves the origin as well as the size, which the one
 * corner handle never had to express. Doing that by adding the delta to the
 * stored offset is wrong on six of the nine anchors: the offset is measured FROM
 * the anchor, so on an end anchor it is an inset and the same pointer motion
 * writes the opposite number, and on a centre anchor half of it.
 *
 * So the arithmetic goes through the edges in slot coordinates, where a drag is
 * just "this edge moved and that one did not", and the offset is derived back out
 * afterwards. [PlacedBox] positions by the same two rules, the alignment bias and
 * the inward sign, so the inversion here is its inverse and not a second opinion.
 *
 * A clamp at the minimum holds the dragged edge and leaves the opposite one where
 * it was, rather than letting the widget walk sideways once it has stopped
 * shrinking.
 */
internal fun canvasResize(
    edge: ResizeEdge,
    startXDp: Float,
    startYDp: Float,
    startWDp: Float,
    startHDp: Float,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    slotWDp: Float,
    slotHDp: Float,
    hBias: Float,
    vBias: Float,
    minDp: Float = MIN_WIDGET_DP,
): ResizedPlacement {
    val (x, w) = resizeAxis(edge.h, startXDp, startWDp, accumXPx / density, slotWDp, hBias, minDp)
    val (y, h) = resizeAxis(edge.v, startYDp, startHDp, accumYPx / density, slotHDp, vBias, minDp)
    return ResizedPlacement(x, y, w, h)
}

/**
 * One axis of [canvasResize]: the stored offset and extent in, the same two out.
 *
 * [side] is -1 for the leading edge, +1 for the trailing one, 0 for an axis this
 * handle does not touch.
 */
private fun resizeAxis(
    side: Int,
    startDp: Float,
    startExtentDp: Float,
    deltaDp: Float,
    slotDp: Float,
    bias: Float,
    minDp: Float,
): Pair<Float, Float> {
    if (side == 0) return startDp to startExtentDp
    // An offset counts inward from its own edge, so a trailing anchor stores the
    // negation of the slot-space position. Same rule PlacedBox draws by.
    val sign = if (bias > 0.5f) -1f else 1f
    val lead0 = bias * (slotDp - startExtentDp) + sign * startDp
    val trail0 = lead0 + startExtentDp

    val lead1 = if (side < 0) lead0 + deltaDp else lead0
    val trail1 = if (side > 0) trail0 + deltaDp else trail0
    val extent = (trail1 - lead1).coerceAtLeast(minDp)
    // Re-derive the moving edge after the clamp: the still one is the one the
    // gesture did not touch, and it must not drift because the other bottomed out.
    val lead = if (side < 0) trail1 - extent else lead1

    return sign * (lead - bias * (slotDp - extent)) to extent
}

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
