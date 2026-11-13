package hivens.ui.layers

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize

/**
 * Marks the part of a [Layer]'s content that is the layer itself, as opposed to the
 * rest of the window it is drawn over: a dialog's card, a sheet's panel.
 *
 * A press there belongs to the layer whether or not anything in it took the press,
 * so a modal never reads a press on a plain stretch of its own body as a press
 * outside it. A press beside the body, on a scrim or on nothing, is still outside.
 * [AnchoredLayer] marks what it places by itself when it is modal.
 */
fun Modifier.layerBody(): Modifier = this then LayerBodyElement

private data object LayerBodyElement : ModifierNodeElement<LayerBodyNode>() {
    override fun create() = LayerBodyNode()
    override fun update(node: LayerBodyNode) = Unit
}

/**
 * Consumes the press on the main pass, after everything inside has had it, so a
 * control inside still gets it first and a catcher under the body sees it taken.
 */
private class LayerBodyNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main || pointerEvent.type != PointerEventType.Press) return
        for (change in pointerEvent.changes) {
            if (change.pressed && !change.previousPressed) change.consume()
        }
    }

    override fun onCancelPointerInput() = Unit
}
