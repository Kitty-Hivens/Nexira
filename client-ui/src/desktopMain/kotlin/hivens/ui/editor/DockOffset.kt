package hivens.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize

/**
 * Where a floating editor panel has been dragged to, kept inside the window.
 *
 * The panels are anchored to a corner and moved by a drag on their header. Held
 * unclamped, a drag could carry one past the edge with nothing left to grab, and
 * the only way back was to close the panel and open it again -- which is not a
 * recovery anyone finds by looking.
 *
 * Session-scoped on purpose: a dock position is where you put it while working on
 * something, not a setting.
 */
class DockOffset internal constructor(private val windowSize: () -> IntSize) {
    var value by mutableStateOf(Offset.Zero)
        private set

    /** Panel bounds, so the clamp knows how much of it must stay reachable. */
    var panelSize: IntSize = IntSize.Zero

    fun drag(delta: Offset) {
        val window = windowSize()
        if (window.width <= 0 || window.height <= 0) {
            value += delta
            return
        }
        // Half the panel's shorter side, or a fixed strip when it has not been
        // measured yet: enough of it stays on screen to grab and drag back.
        val keep = minOf(panelSize.width, panelSize.height).takeIf { it > 0 }?.div(2) ?: MIN_VISIBLE_PX
        val next = value + delta
        value = Offset(
            x = next.x.coerceIn(-(window.width - keep).toFloat(), (window.width - keep).toFloat()),
            y = next.y.coerceIn(-(window.height - keep).toFloat(), (window.height - keep).toFloat()),
        )
    }

    private companion object {
        const val MIN_VISIBLE_PX = 80
    }
}

@Composable
fun rememberDockOffset(): DockOffset {
    val windowInfo = LocalWindowInfo.current
    return remember { DockOffset { windowInfo.containerSize } }
}
