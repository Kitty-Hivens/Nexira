package hivens.ui.screens.console

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// Pixel-primary scroll state for the log canvas. The canvas draws by pixel offset,
// so the offset -- not a line index -- is the source of truth: uniform wheel/drag
// motion and a native scrollbar regardless of per-line (wrap) height. The canvas
// feeds back [viewportPx] and [contentHeightPx] each layout; [offsetPx] stays
// clamped to the resulting range.
//
// Stability across geometry changes (a line above the viewport resolving from
// estimate to measured, or history paged in at the top) is handled by the canvas
// via [shiftBy] with the exact pixel delta -- retiring the old approximate
// line-height nudge -- so the visible line stays put instead of the offset drifting.
internal class LogScrollState {
    var offsetPx by mutableFloatStateOf(0f)
        private set

    // Written by the canvas on each measure pass.
    var viewportPx by mutableIntStateOf(0)
        internal set
    var contentHeightPx by mutableIntStateOf(0)
        internal set

    val maxOffset: Float get() = (contentHeightPx - viewportPx).coerceAtLeast(0).toFloat()

    /** At (or within a hair of) the bottom -- the sticky-follow condition. */
    val atBottom: Boolean get() = offsetPx >= maxOffset - BOTTOM_EPSILON

    /** Set the absolute offset, clamped. Returns the pixels actually consumed. */
    fun scrollTo(px: Float): Float {
        val before = offsetPx
        offsetPx = px.coerceIn(0f, maxOffset)
        return offsetPx - before
    }

    /** Relative scroll (wheel / keyboard), clamped. Returns pixels consumed. */
    fun scrollBy(deltaPx: Float): Float = scrollTo(offsetPx + deltaPx)

    fun scrollToBottom() { offsetPx = maxOffset }

    /**
     * Adjust the offset by [deltaPx] WITHOUT re-clamping intent: used when content
     * above the viewport changed height by [deltaPx], so the visible content keeps
     * its screen position. Still clamped to the (new) range.
     */
    fun shiftBy(deltaPx: Float) { offsetPx = (offsetPx + deltaPx).coerceIn(0f, maxOffset) }

    suspend fun animateScrollTo(px: Float, spec: AnimationSpec<Float> = tween(220)) {
        val target = px.coerceIn(0f, maxOffset)
        val start = offsetPx
        animate(start, target, animationSpec = spec) { value, _ -> offsetPx = value.coerceIn(0f, maxOffset) }
    }

    suspend fun animateScrollBy(deltaPx: Float, spec: AnimationSpec<Float> = tween(220)) =
        animateScrollTo(offsetPx + deltaPx, spec)

    /**
     * A Compose-Desktop scrollbar adapter over this state, so the existing
     * VerticalScrollbar/HorizontalScrollbar UI drives the pixel offset directly.
     */
    fun scrollbarAdapter(): ScrollbarAdapter = object : ScrollbarAdapter {
        override val scrollOffset: Double get() = offsetPx.toDouble()
        override val contentSize: Double get() = contentHeightPx.toDouble()
        override val viewportSize: Double get() = viewportPx.toDouble()
        override suspend fun scrollTo(scrollOffset: Double) { this@LogScrollState.scrollTo(scrollOffset.toFloat()) }
    }

    private companion object {
        // Small slack so a fractional resting offset still reads as "following".
        const val BOTTOM_EPSILON = 1.5f
    }
}
