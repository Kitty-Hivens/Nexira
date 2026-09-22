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

/**
 * The smallest a widget may be dragged to when it declares no floor of its own,
 * so it cannot collapse out of reach.
 *
 * A fallback. A widget that says what it needs is held to that instead, and the
 * same number bounds what the renderer draws, so the handle cannot stop
 * somewhere the drawing disagrees with.
 */
internal const val MIN_WIDGET_DP = 48f

/**
 * What a resize gesture is allowed to produce on one axis.
 *
 * Read off the widget's own declaration, with [MIN_WIDGET_DP] standing in for a
 * floor nobody named and no ceiling at all for a ceiling nobody named.
 */
internal data class ResizeBounds(val minDp: Float, val maxDp: Float) {
    fun hold(v: Float): Float = v.coerceIn(minDp, maxOf(minDp, maxDp))

    companion object {
        /**
         * No opinion: the editor's own floor, and as large as the slot allows.
         *
         * Infinity rather than [Float.MAX_VALUE], because "no ceiling" has to be
         * distinguishable from a very large one. A reader asking isFinite about
         * MAX_VALUE is told yes, and the range guide then drew a rectangle the
         * size of the number.
         */
        val OPEN = ResizeBounds(MIN_WIDGET_DP, Float.POSITIVE_INFINITY)

        fun of(declaredMin: Int, declaredMax: Int): ResizeBounds = ResizeBounds(
            minDp = if (declaredMin > 0) declaredMin.toFloat() else MIN_WIDGET_DP,
            maxDp = if (declaredMax > 0) declaredMax.toFloat() else Float.POSITIVE_INFINITY,
        )
    }
}

/**
 * Where a guide marking an extent of [extentDp] sits inside a widget currently
 * [ownDp] across, for an anchor whose bias on that axis is [bias].
 *
 * The anchored edge is the one that does not move while the handle is dragged,
 * so the guide has to hang off the same corner the resize pivots on. Same bias
 * arithmetic the renderer positions by, so the guide lands where the widget will.
 */
internal fun guideLeadDp(ownDp: Float, extentDp: Float, bias: Float): Float = bias * (ownDp - extentDp)

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
 * The size a placement stores is a CEILING, not an extent. The renderer draws a
 * widget at its own content and never past the claim, so a widget whose content
 * does not fill draws smaller than the number stored for it. The origin has to
 * hang off what the widget DREW, passed in as [liveDrawnWDp] and [liveDrawnHDp],
 * and never off the claim. Growing the claim past the content is a ceiling the
 * pixels never reach, and hanging the origin off it slid the whole widget across
 * the slot instead of resizing it: the reported "drag the top edge and the player
 * just moves". The claim still records the drag, so a widget that DOES fill grows
 * into it.
 */
internal fun canvasResize(
    edge: ResizeEdge,
    startXDp: Float,
    startYDp: Float,
    startDrawnWDp: Float,
    startDrawnHDp: Float,
    liveDrawnWDp: Float,
    liveDrawnHDp: Float,
    startClaimWDp: Float,
    startClaimHDp: Float,
    accumXPx: Float,
    accumYPx: Float,
    density: Float,
    slotWDp: Float,
    slotHDp: Float,
    hBias: Float,
    vBias: Float,
    widthBounds: ResizeBounds = ResizeBounds.OPEN,
    heightBounds: ResizeBounds = ResizeBounds.OPEN,
): ResizedPlacement {
    val (x, w) = resizeAxis(edge.h, startXDp, startDrawnWDp, liveDrawnWDp, startClaimWDp, accumXPx / density, slotWDp, hBias, widthBounds)
    val (y, h) = resizeAxis(edge.v, startYDp, startDrawnHDp, liveDrawnHDp, startClaimHDp, accumYPx / density, slotHDp, vBias, heightBounds)
    return ResizedPlacement(x, y, w, h)
}

/**
 * One axis of [canvasResize]. Takes the stored offset, the drawn extent at the
 * start of the gesture, the drawn extent right now, and the stored claim. Returns
 * the new stored offset and the new stored claim.
 *
 * [side] is -1 for the leading edge, +1 for the trailing one, 0 for an axis this
 * handle does not touch. An untouched axis keeps its stored claim exactly, so a
 * one-axis resize never rewrites the other axis to a measured number.
 */
private fun resizeAxis(
    side: Int,
    startOffsetDp: Float,
    startDrawnDp: Float,
    liveDrawnDp: Float,
    startClaimDp: Float,
    deltaDp: Float,
    slotDp: Float,
    bias: Float,
    bounds: ResizeBounds,
): Pair<Float, Float> {
    if (side == 0) return startOffsetDp to startClaimDp
    // An offset counts inward from its own edge, so a trailing anchor stores the
    // negation of the slot-space position. Same rule PlacedBox draws by.
    val sign = if (bias > 0.5f) -1f else 1f
    // The claim the drag asks for, measured off what the widget drew rather than
    // off the old stored ceiling, so a handle sitting on the content moves the
    // number with the content and not with a stale claim it drew nowhere near.
    val target = if (side < 0) startDrawnDp - deltaDp else startDrawnDp + deltaDp
    val claim = bounds.hold(target)
    // What the widget will actually DRAW at that claim, which is all the origin may
    // hang off. Shrinking cuts the content, so the drawn size follows the claim
    // down exactly. Growing cannot make content-limited pixels any larger, so the
    // drawn size holds at what is on screen (liveDrawn) and only a widget that
    // fills climbs toward the claim.
    val drawn = if (claim <= startDrawnDp) claim else liveDrawnDp.coerceIn(startDrawnDp, claim)
    // The fixed-edge anchor, taken from the drawn box at the start of the gesture.
    val lead0 = bias * (slotDp - startDrawnDp) + sign * startOffsetDp
    val trail0 = lead0 + startDrawnDp
    // Re-derive the moving edge from the drawn size: the edge the gesture did not
    // touch stays where it was, and the other one follows the pixels.
    val lead = if (side < 0) trail0 - drawn else lead0

    return sign * (lead - bias * (slotDp - drawn)) to claim
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
