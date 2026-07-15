package hivens.ui.debug

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect

enum class DebugNodeKind { Slot, Widget }

data class DebugNode(val rect: Rect, val label: String, val kind: DebugNodeKind)

/**
 * Window-space bounds reported by the debug decorators, read by [DebugOverlay] to
 * draw outlines + labels. Snapshot-backed so the overlay redraws when a node moves.
 * UI-thread only (layout callbacks report; the overlay draw reads) -- no locking.
 */
class DebugBoundsRegistry {
    val nodes = mutableStateMapOf<String, DebugNode>()

    /** Idempotent: only writes (and so only invalidates the overlay) on a real change. */
    fun report(key: String, node: DebugNode) {
        val prev = nodes[key]
        if (prev == null || prev.rect != node.rect || prev.label != node.label) nodes[key] = node
    }

    fun remove(key: String) { nodes.remove(key) }
    fun clear() { nodes.clear() }
}
