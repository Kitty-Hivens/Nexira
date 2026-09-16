package hivens.widget.model

/**
 * How a flow slot sizes one widget.
 *
 * A widget carries one [Placement] whatever slot it sits in, and a flow reads
 * three of its fields: the weight, and the two sizes as an upper bound. The rest
 * belongs to a placement slot and is ignored here rather than cleared, so a slot
 * flipped between modes and back keeps both arrangements.
 *
 * This used to be a precedence rule across three separate instance fields,
 * written out twice inside the renderer, once per flow branch, and nowhere as a
 * statement anybody could read or test.
 */
sealed interface FlowPlacement {

    /** A weighted share of the slot's main axis. */
    data class Weighted(val weight: Float) : FlowPlacement

    /**
     * A resized widget: an upper bound, not a fixed extent. Content that fills
     * grows into it; content that wraps keeps its natural size, so dragging the
     * handle past the content does not inflate the box with empty space. Either
     * dimension may be 0, meaning unbounded on that axis.
     */
    data class Bounded(val widthDp: Float, val heightDp: Float) : FlowPlacement

    /** Natural size, in source order. */
    data object Natural : FlowPlacement
}

/**
 * What a flow slot honours for this widget.
 *
 * Weight wins over an explicit size on purpose: resizing a weighted widget must
 * not strip its flex, or the weighted centre region stops filling between the
 * rails.
 */
fun WidgetInstance.flowPlacement(): FlowPlacement {
    val p = placement ?: return FlowPlacement.Natural
    if (p.weight > 0f) return FlowPlacement.Weighted(p.weight)
    if (p.width <= 0f && p.height <= 0f) return FlowPlacement.Natural
    return FlowPlacement.Bounded(widthDp = p.width, heightDp = p.height)
}
