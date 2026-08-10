package hivens.widget.model

/**
 * How a flow slot -- [SlotOrientation.Row] or [SlotOrientation.Column] -- sizes
 * one widget.
 *
 * [WidgetInstance] carries `weight`, `canvas` and `cell` as independent fields,
 * and every instance can hold all three at once even though at most one means
 * anything for the slot it sits in. The renderer resolves that with a
 * precedence rule which existed twice, once per flow branch, and nowhere as a
 * statement anybody could read or test.
 *
 * Which field wins where:
 *
 * | Slot      | Honoured                                  | Ignored          |
 * |-----------|-------------------------------------------|------------------|
 * | Row/Column| `weight`, else `canvas` width/height as a bound | `canvas` x/y/z, `cell` |
 * | Grid      | nothing -- cells are uniform              | all three        |
 * | Canvas    | `canvas` offset, exact size, z            | `weight`, `cell` |
 * | CubeGrid  | `cell` address and span                   | `weight`, `canvas` |
 *
 * The ignored fields are kept rather than cleared: a slot flipped to another
 * orientation and back would otherwise lose the arrangement it had, from one
 * stray click on the orientation menu.
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
    if (weight > 0f) return FlowPlacement.Weighted(weight)
    val size = canvas ?: return FlowPlacement.Natural
    if (size.width <= 0f && size.height <= 0f) return FlowPlacement.Natural
    return FlowPlacement.Bounded(widthDp = size.width, heightDp = size.height)
}
