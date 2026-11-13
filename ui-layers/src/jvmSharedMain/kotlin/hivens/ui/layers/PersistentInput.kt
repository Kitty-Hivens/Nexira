package hivens.ui.layers

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf

/**
 * Marks a text field that holds the keyboard for as long as it is on screen: a
 * console's command line, a screen's one search box that is always ready to type
 * into.
 *
 * Escape never takes the focus out of such a field. It goes on to the window's
 * dismiss stack at once, as it would with no field focused at all. An ordinary
 * field of the window gives up the keyboard on the first Escape instead, so the
 * window's own shortcuts work again (see [DismissStack]).
 *
 * Put it ahead of the field's own modifiers, so it sees the field's focus.
 */
fun Modifier.persistentInput(): Modifier = this then PersistentInputElement

private data object PersistentInputElement : ModifierNodeElement<PersistentInputNode>() {
    override fun create() = PersistentInputNode()
    override fun update(node: PersistentInputNode) = Unit
}

/**
 * Counts itself on the window's stack while the field holds the focus. A count
 * rather than a flag on the field, because the stack has to answer for the window
 * as a whole and the next field takes the focus before the last one lets go.
 */
private class PersistentInputNode : Modifier.Node(), FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var counted: DismissStack? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus) {
            if (counted == null) counted = currentValueOf(LocalWindowLayers)?.dismiss?.also { it.persistentFocused() }
        } else {
            release()
        }
    }

    override fun onDetach() = release()

    private fun release() {
        counted?.persistentReleased()
        counted = null
    }
}
