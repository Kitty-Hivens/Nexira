package hivens.ui.editor

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import hivens.widget.api.ViewportHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Scrolls the page a drag is on while the pointer sits near the page's edge.
 *
 * A gesture says when it starts, where the pointer is as it moves, and when it
 * ends. Between those, once a frame, the page under it moves by [autoScrollStep]
 * and the gesture is told how far, so whatever it is dragging can stay under the
 * pointer instead of travelling off with the content.
 *
 * Between drags [run] waits without asking for a frame, so an editor nobody is
 * dragging in costs nothing. One instance per gesture owner and one [run] for it:
 * two loops over the same instance would scroll twice a frame.
 */
internal class EdgeAutoScroll(private val viewport: () -> ViewportHandle?) {
    private val dragging = MutableStateFlow(false)
    private var pointer: Offset? = null
    private var onScrolled: (Offset) -> Unit = {}

    /** A drag began at [pointerInWindow]. [onScrolled] hears how far the view moved on, in px on each axis. */
    fun start(pointerInWindow: Offset, onScrolled: (Offset) -> Unit) {
        pointer = pointerInWindow
        this.onScrolled = onScrolled
        dragging.value = true
    }

    fun move(pointerInWindow: Offset) {
        pointer = pointerInWindow
    }

    fun stop() {
        dragging.value = false
        pointer = null
        onScrolled = {}
    }

    suspend fun run(density: () -> Float) {
        while (true) {
            dragging.first { it }
            while (dragging.value) {
                withFrameNanos { }
                val view = viewport() ?: continue
                val at = pointer ?: continue
                val zone = AUTO_SCROLL_ZONE_DP * density()
                val max = AUTO_SCROLL_MAX_STEP_DP * density()
                val b = view.bounds
                // Each axis the view moves along, on its own: a page has one, a map
                // two, and a widget held in a map's corner moves it diagonally.
                val step = Offset(
                    if (view.movesX) autoScrollStep(at.x, b.left, b.right, zone, max) else 0f,
                    if (view.movesY) autoScrollStep(at.y, b.top, b.bottom, zone, max) else 0f,
                )
                if (step == Offset.Zero) continue
                val moved = view.scrollBy(step)
                if (moved != Offset.Zero) onScrolled(moved)
            }
        }
    }
}
