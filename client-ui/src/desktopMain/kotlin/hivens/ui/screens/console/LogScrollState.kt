package hivens.ui.screens.console

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Pixel-primary scroll state for the log canvas. The canvas draws by pixel offset,
// so the offset -- not a line index -- is the source of truth: uniform wheel/drag
// motion and a native scrollbar regardless of per-line (wrap) height. The canvas
// feeds back [viewportPx] and [contentHeightPx] each layout; [offsetPx] stays
// clamped to the resulting range.
//
// [follow] is an explicit tail-follow intent, not a derived "is the offset near the
// bottom" test. It has to be explicit because in wrap mode the newly appended tail
// lines are laid out at an estimate first and firm up to their real height during
// the draw pass -- so a position-derived "at bottom" would flip to false the instant
// the real (taller) heights land, dropping the newest lines below the fold. With the
// flag, the canvas re-pins to the true bottom after the tail measures.
class LogScrollState {
    var offsetPx by mutableFloatStateOf(0f)
        private set

    var viewportPx by mutableIntStateOf(0)
        internal set
    var contentHeightPx by mutableIntStateOf(0)
        internal set

    // Tail-follow intent. True until the user scrolls up; re-armed when they scroll
    // back to the bottom (or press G / resume).
    var follow by mutableStateOf(true)
        private set

    val maxOffset: Float get() = (contentHeightPx - viewportPx).coerceAtLeast(0).toFloat()

    /** The footer's follow / paused chip reads intent, not raw position. */
    val atBottom: Boolean get() = follow

    private fun clamp(px: Float) = px.coerceIn(0f, maxOffset)
    private fun refreshFollow() { follow = offsetPx >= maxOffset - BOTTOM_EPSILON }

    /** User-initiated absolute scroll (wheel / scrollbar / keys / tap). Updates the
     *  follow intent from where it lands. Returns pixels consumed. */
    fun scrollTo(px: Float): Float {
        val before = offsetPx
        offsetPx = clamp(px)
        refreshFollow()
        return offsetPx - before
    }

    fun scrollBy(deltaPx: Float): Float = scrollTo(offsetPx + deltaPx)

    /** Pin to the bottom and arm follow (G / resume / jump-to-bottom). */
    fun scrollToBottom() { offsetPx = maxOffset; follow = true }

    /** Re-pin to the bottom only if already following -- called after the tail's real
     *  heights land, so following the tail keeps showing the newest lines. */
    fun stickIfFollowing() { if (follow) offsetPx = maxOffset }

    /** Re-clamp to the current range on a viewport change, preserving follow: if
     *  following, stay pinned to the (new) bottom; else just clamp. */
    fun reclamp() { offsetPx = if (follow) maxOffset else clamp(offsetPx) }

    /**
     * Adjust the offset by [deltaPx] for a content-height change ABOVE the viewport
     * (history paged in at the top), keeping the visible line in place. Does not touch
     * follow -- paging history is a read-up gesture, not tail-following. The delta is
     * exact for no-wrap (uniform height) and approximate for wrap (one row per line)
     * until those lines re-measure.
     */
    fun shiftBy(deltaPx: Float) { offsetPx = clamp(offsetPx + deltaPx) }

    suspend fun animateScrollTo(px: Float, spec: AnimationSpec<Float> = tween(220)) {
        val target = clamp(px)
        animate(offsetPx, target, animationSpec = spec) { value, _ -> offsetPx = clamp(value) }
        refreshFollow()
    }

    suspend fun animateScrollBy(deltaPx: Float, spec: AnimationSpec<Float> = tween(220)) =
        animateScrollTo(offsetPx + deltaPx, spec)

    /** A Compose-Desktop scrollbar adapter over this state; dragging it is a user
     *  scroll, so it updates the follow intent. */
    fun scrollbarAdapter(): ScrollbarAdapter = object : ScrollbarAdapter {
        override val scrollOffset: Double get() = offsetPx.toDouble()
        override val contentSize: Double get() = contentHeightPx.toDouble()
        override val viewportSize: Double get() = viewportPx.toDouble()
        override suspend fun scrollTo(scrollOffset: Double) { this@LogScrollState.scrollTo(scrollOffset.toFloat()) }
    }

    private companion object {
        // Slack for arming follow when the user scrolls near (old widget used ~16 px).
        const val BOTTOM_EPSILON = 16f
    }
}
