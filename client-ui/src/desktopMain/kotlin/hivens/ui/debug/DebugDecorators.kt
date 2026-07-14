package hivens.ui.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import hivens.widget.api.SlotChromeModifier
import hivens.widget.api.WidgetDecorator

// Debug seams provided at the shell root, but only on non-release builds -- on a
// release build the identity defaults below stand and nothing is wrapped. Both are
// REPORT-ONLY: they push each node's window bounds into the registry while the
// overlay is enabled and add no visual (DebugOverlay draws from the registry). The
// editor's own decorator / chrome-modifier override these inside its subtree, so
// debug chrome yields to edit-mode chrome while editing.

val IdentityWidgetDecorator: WidgetDecorator = { _, _, _, _, content -> content() }
val IdentitySlotChromeModifier: SlotChromeModifier = { _, _ -> Modifier }

// Border tint by a widget wrapper's recomposition count: cool (composed once)
// through hot (many). Alpha rises with the count so hot nodes stand out.
private fun recomposeTint(count: Int): Color = when {
    count <= 1 -> Color(0x8033FF77)
    count <= 3 -> Color(0xA0FFEE33)
    count <= 8 -> Color(0xC0FF9933)
    else -> Color(0xE0FF3333)
}

/** Reports every widget's window bounds; wraps content in a bare Box (no draw). */
fun debugWidgetDecorator(state: DebugOverlayState): WidgetDecorator =
    { _, _, _, instance, content ->
        val key = "w:${instance.instanceId}"
        val label = "${instance.kind.value} #${instance.instanceId.take(6)}"
        DisposableEffect(key) { onDispose { state.bounds.remove(key) } }
        // Recomposition tally: a plain (non-snapshot) holder bumped each time this
        // wrapper composes. Reading it in the draw phase never re-invalidates, so it
        // cannot loop. The count is the wrapper's own recompositions (prop / reorder
        // churn), not the widget's deeper internal recomposition.
        val recompositions = remember { intArrayOf(0) }
        recompositions[0]++
        Box(
            Modifier
                .onGloballyPositioned { coords ->
                    // Report whenever the overlay is on; the Canvas filters which
                    // kinds to draw per-facet, so a facet toggle is an instant redraw.
                    if (state.enabled) {
                        state.bounds.report(key, DebugNode(coords.boundsInWindow(), label, DebugNodeKind.Widget))
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (state.enabled && state.recomposition) {
                        drawRect(recomposeTint(recompositions[0]), style = Stroke(width = 2f))
                    }
                },
        ) { content() }
    }

/** Zero-footprint Modifier per slot that reports the slot's window bounds. */
fun debugSlotChromeModifier(state: DebugOverlayState): SlotChromeModifier =
    { path, content ->
        Modifier.composed {
            val key = "s:$path"
            val label = "${path.leafSlot.value} [${content.orientation.name}]"
            DisposableEffect(key) { onDispose { state.bounds.remove(key) } }
            Modifier.onGloballyPositioned { coords ->
                if (state.enabled) {
                    state.bounds.report(key, DebugNode(coords.boundsInWindow(), label, DebugNodeKind.Slot))
                }
            }
        }
    }
