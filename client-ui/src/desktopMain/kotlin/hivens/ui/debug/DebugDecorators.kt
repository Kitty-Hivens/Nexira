package hivens.ui.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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

/** Reports every widget's window bounds; wraps content in a bare Box (no draw). */
fun debugWidgetDecorator(state: DebugOverlayState): WidgetDecorator =
    { _, _, _, instance, content ->
        val key = "w:${instance.instanceId}"
        val label = "${instance.kind.value} #${instance.instanceId.take(6)}"
        DisposableEffect(key) { onDispose { state.bounds.remove(key) } }
        Box(
            Modifier.onGloballyPositioned { coords ->
                if (state.enabled && state.widgetBounds) {
                    state.bounds.report(key, DebugNode(coords.boundsInWindow(), label, DebugNodeKind.Widget))
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
                if (state.enabled && state.slotBounds) {
                    state.bounds.report(key, DebugNode(coords.boundsInWindow(), label, DebugNodeKind.Slot))
                }
            }
        }
    }
