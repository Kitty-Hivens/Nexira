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
    private var onScrolled: (Float) -> Unit = {}

    /** A drag began at [pointerInWindow]. [onScrolled] hears how far the page moved, in px along its axis. */
    fun start(pointerInWindow: Offset, onScrolled: (Float) -> Unit) {
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

    /** The page this drag is on scrolls along x, or null when it is on no page. */
    val horizontal: Boolean? get() = viewport()?.horizontal

    suspend fun run(density: () -> Float) {
        while (true) {
            dragging.first { it }
            while (dragging.value) {
                withFrameNanos { }
                val page = viewport() ?: continue
                val at = pointer ?: continue
                val d = density()
                val b = page.bounds
                val step = if (page.horizontal) {
                    autoScrollStep(at.x, b.left, b.right, AUTO_SCROLL_ZONE_DP * d, AUTO_SCROLL_MAX_STEP_DP * d)
                } else {
                    autoScrollStep(at.y, b.top, b.bottom, AUTO_SCROLL_ZONE_DP * d, AUTO_SCROLL_MAX_STEP_DP * d)
                }
                if (step == 0f) continue
                val moved = page.scrollBy(step)
                if (moved != 0f) onScrolled(moved)
            }
        }
    }
}
