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
 *    what it takes when nobody has chosen one. The palette draws its tile and
 *    its drag ghost at this. It is not written into the layout as a claim: a
 *    claim is a maximum, and this is a shape.
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
     * The upper bound to draw the widget under, given a claim of [claimDp].
     *
     * A claim is a maximum, so this is normally the claim. Two departures, and
     * each is the reason the type exists:
     *
     *  * under the declared floor the bound is the floor, because honouring a
     *    claim that small removes content and says nothing about it;
     *  * over the declared ceiling the bound is the ceiling, because the space
     *    past it is space the widget will not paint.
     *
     * No claim means no bound, and the ceiling is NOT substituted for one. It was,
     * briefly, on the reasoning that a widget should never hold space it will not
     * paint -- but a widget nobody has sized is already drawing at its own size,
     * so the ceiling adds nothing there and takes something away: a declaration is
     * one number and several widgets are only that tall in one configuration. A
     * disc whose ceiling describes its WIDTH had that ceiling applied to its
     * height, and the caption underneath it was clipped to nothing.
     */
    fun boundWidth(claimDp: Float): Float = bound(claimDp, minWidth, maxWidth)

    /** The upper bound to draw the widget's height under. See [boundWidth]. */
    fun boundHeight(claimDp: Float): Float = bound(claimDp, minHeight, maxHeight)

    companion object {
        /** A widget that says nothing about its size, which is most of them. */
        val UNDECLARED = WidgetSizing()

        private fun bound(claim: Float, min: Int, max: Int): Float {
            if (claim <= 0f) return 0f
            val raised = if (min > 0) claim.coerceAtLeast(min.toFloat()) else claim
            return if (max > 0) raised.coerceAtMost(max.toFloat()) else raised
        }
    }
}
