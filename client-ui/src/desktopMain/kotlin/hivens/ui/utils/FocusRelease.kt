package hivens.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize

/**
 * Lets go of the keyboard focus on a press outside whatever holds it, the way a
 * desktop field gives up its caret when the reader clicks somewhere else.
 *
 * Compose keeps focus where it was until something else asks for it, and most of
 * what a reader clicks never asks: a card, a rail, an empty stretch of page. A
 * search box therefore went on showing its caret and its accent long after the
 * reader had moved on.
 *
 * Only a press outside the focused element's bounds counts. Some elements take
 * the focus for their keys and are clicked inside without asking for it again: the
 * version picker closes on Escape and its rows are plain clickables, and a slider
 * takes arrow keys once the pointer is over it. Releasing on any press left both
 * deaf to their keys after the first click inside them.
 *
 * Only a plain primary press. A right press opens a field's own menu for the
 * selection it holds, and a shift press extends that selection: letting go of the
 * focus first dropped the selection either one was about to act on.
 *
 * Read on the first pass and never consumed, so every press still reaches what it
 * was meant for.
 */
fun Modifier.releaseFocusOnPress(): Modifier = this then ReleaseFocusElement

private data object ReleaseFocusElement : ModifierNodeElement<ReleaseFocusNode>() {
    override fun create() = ReleaseFocusNode()
    override fun update(node: ReleaseFocusNode) = Unit
}

/**
 * Asks where the focus is at the moment of the press, through a focus target of
 * its own that never takes the focus itself: it only stands over the shell so it
 * can say which of its descendants holds it, and where.
 */
private class ReleaseFocusNode : DelegatingNode(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private val target = delegate(FocusTargetModifierNode(focusability = Focusability.Never))

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial || pointerEvent.type != PointerEventType.Press) return
        if (!pointerEvent.buttons.isPrimaryPressed || pointerEvent.keyboardModifiers.isShiftPressed) return
        val at = pointerEvent.changes.firstOrNull()?.position ?: return
        val focused = target.getFocusedRect() ?: return
        if (!focused.contains(at)) currentValueOf(LocalFocusManager).clearFocus()
    }

    override fun onCancelPointerInput() = Unit
}
