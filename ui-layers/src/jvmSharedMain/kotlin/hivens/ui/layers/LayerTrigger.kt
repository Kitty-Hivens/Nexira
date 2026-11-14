package hivens.ui.layers

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf

/**
 * Marks a control a layer can be opened from with the keyboard: a button, a menu's
 * row, the kebab of a list row.
 *
 * A focusable layer shown while the control holds the focus gives the focus back to
 * it when it goes, however deep the control sits in focus groups. Without the mark the
 * layer can only hand the focus back to the group it took it from, and that group
 * enters at its first control, which is the top of the window more often than not.
 *
 * When the control itself is gone by then, a row of a menu that closed before the
 * dialog it opened, the focus goes to whatever the layer that held the control was
 * opened from, and so on outwards. So it does when the control is now another one:
 * a row of a lazy list that left while its confirmation was open, its slot reused
 * for a row that came after, is not where the keyboard goes back to.
 *
 * Put it ahead of the control's own clickable or focusable, so it sees that one's focus.
 */
fun Modifier.layerTrigger(): Modifier = this then LayerTriggerElement

private data object LayerTriggerElement : ModifierNodeElement<LayerTriggerNode>() {
    override fun create() = LayerTriggerNode()
    override fun update(node: LayerTriggerNode) = Unit
}

/**
 * Tells the window's layers while the control below it holds the focus, and takes the
 * focus back for it when a layer asks.
 *
 * Counts the lives it has had: a lazy list keeps the node of a row that left and
 * hands it, still attached, to the next row its slot is reused for, and a trigger
 * noted in an earlier life is not this control any more.
 */
internal class LayerTriggerNode :
    Modifier.Node(), FocusEventModifierNode, FocusRequesterModifierNode, CompositionLocalConsumerModifierNode {

    private var noted: WindowLayers? = null

    /** Which life of the node this is, moved on whenever it is reused or detached. */
    var life: Int = 0
        private set

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) {
            val layers = currentValueOf(LocalWindowLayers) ?: return
            noted = layers
            layers.triggerFocused(LayerTrigger(this, currentValueOf(LocalResident), life))
        } else {
            release()
        }
    }

    override fun onReset() {
        life++
        release()
    }

    override fun onDetach() {
        life++
        release()
    }

    /**
     * Moves the focus back onto the control noted in [life]. False when it is gone, is
     * another control now, or would not take it.
     */
    fun takeFocus(life: Int): Boolean = isAttached && life == this.life && requestFocus()

    private fun release() {
        noted?.triggerLeft(this)
        noted = null
    }
}

/**
 * A control marked with [layerTrigger] as it held the focus: its node, the life of
 * the node it was noted in, and the layer it is drawn in, null for the window's own
 * content.
 */
internal class LayerTrigger(val node: LayerTriggerNode, val owner: LayerResident?, val life: Int) {
    /** Moves the focus back onto the control, if it is still the one noted. */
    fun takeFocus(): Boolean = node.takeFocus(life)
}
