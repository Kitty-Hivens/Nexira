package hivens.widget.model

/**
 * What a widget needs, wants and can use, per axis, in dp.
 *
 * Nothing used to know this. A [Placement] is a claim on territory and the
 * renderer applies it as a maximum, which is right, but with no floor under it a
 * claim smaller than the widget cut the widget off and said nothing: a column
 * player given 272 of the 304 it draws lost its transport and looked like a
 * player with no play button. With no ceiling over it a claim larger than the
 * widget reserved space the widget never painted. And with neither, the editor
 * could not say where a resize handle was allowed to go, or how much room a
 * widget from the palette was about to take, because the answer did not exist
 * until the widget was mounted and measured.
 *
 * Three numbers per axis, and each one answers a question somebody is asking:
 *
 *  * [minWidth] / [minHeight]: below this the content is cut. The editor refuses
 *    to drag past it and the renderer refuses to honour a claim under it.
 *  * [prefWidth] / [prefHeight]: the size the widget looks correct at, which is
 *    what it takes when nobody has chosen one. The palette shows this as the
 *    footprint a drop is about to occupy, and seeds the claim with it.
 *  * [maxWidth] / [maxHeight]: past this the widget draws no more, so holding the
 *    space is holding empty pixels. The editor stops the handle here.
 *
 * Zero on any of the six means undeclared, and every reader falls back to what it
 * did before: no floor, no ceiling, and a size decided by the content. Undeclared
 * is the default because a wrong number is worse than a missing one, and because
 * most widgets in a flow genuinely have no opinion about their width.
 */
data class WidgetSizing(
    val minWidth: Int = 0,
    val minHeight: Int = 0,
    val prefWidth: Int = 0,
    val prefHeight: Int = 0,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
) {
    /** True when this widget says nothing at all, which is the default. */
    val undeclared: Boolean
        get() = this == UNDECLARED

    /**
     * [widthDp] held inside what the widget can use, for a gesture choosing a size.
     *
     * [fallbackMin] stands in where the widget names no floor: the editor still
     * needs one, or a handle drags a widget to nothing and leaves it unreachable.
     */
    fun holdWidth(widthDp: Float, fallbackMin: Float = 0f): Float =
        hold(widthDp, minWidth, maxWidth, fallbackMin)

    /** [heightDp] held inside what the widget can use. See [holdWidth]. */
    fun holdHeight(heightDp: Float, fallbackMin: Float = 0f): Float =
        hold(heightDp, minHeight, maxHeight, fallbackMin)

    /**
     * The upper bound to draw the widget under, given a claim of [claimDp].
     *
     * A claim is a maximum, so this is normally the claim. Three departures, and
     * each is the reason the type exists:
     *
     *  * under the declared floor the bound is the floor, because honouring a
     *    claim that small removes content and says nothing about it;
     *  * over the declared ceiling the bound is the ceiling, because the space
     *    past it is space the widget will not paint;
     *  * with no claim at all the bound is still the ceiling, for the same reason.
     *
     * Zero out means no bound, which is what a widget with no claim and no
     * declared ceiling has always had.
     */
    fun boundWidth(claimDp: Float): Float = bound(claimDp, minWidth, maxWidth)

    /** The upper bound to draw the widget's height under. See [boundWidth]. */
    fun boundHeight(claimDp: Float): Float = bound(claimDp, minHeight, maxHeight)

    companion object {
        /** A widget that says nothing about its size, which is most of them. */
        val UNDECLARED = WidgetSizing()

        private fun hold(v: Float, min: Int, max: Int, fallbackMin: Float): Float {
            val ceiling = if (max > 0) max.toFloat() else Float.MAX_VALUE
            val declaredFloor = if (min > 0) min.toFloat() else fallbackMin
            // The ceiling wins a contradiction, so a widget whose declared maximum
            // sits under the editor's fallback floor is still reachable at its own
            // maximum rather than pinned above it.
            val floor = minOf(declaredFloor, ceiling)
            return v.coerceAtMost(ceiling).coerceAtLeast(floor)
        }

        private fun bound(claim: Float, min: Int, max: Int): Float {
            val raised = if (claim > 0f && min > 0) claim.coerceAtLeast(min.toFloat()) else claim
            return when {
                max <= 0 -> raised.coerceAtLeast(0f)
                raised <= 0f -> max.toFloat()
                else -> raised.coerceAtMost(max.toFloat())
            }
        }
    }
}
