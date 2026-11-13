package hivens.ui.editor

import androidx.compose.runtime.Composable
import hivens.ui.layers.DismissOnBack

/**
 * The editor's way back out, one Escape or back per step, as entries of the
 * window's dismiss stack: preview, the slot menu, the slot selection, and editing
 * itself.
 *
 * Each step is an entry while it is open, so the stack closes whichever was opened
 * last: preview closes before the slot steps it hides, so no press is spent on
 * something nobody can see. Every step is held only while editing, so a selection
 * left set when the session ends is not something Escape can still reach. A reset
 * confirmation is a modal dialog on the window's layers and an entry of its own,
 * above all of these.
 *
 * Declared bottom to top. Two steps that open in the same frame, a right click that
 * selects a slot and opens its menu, are stamped in that order.
 */
@Composable
internal fun EditorBackOut(
    editing: Boolean,
    onLeave: () -> Unit,
    selection: Boolean,
    onClearSelection: () -> Unit,
    slotMenu: Boolean,
    onCloseSlotMenu: () -> Unit,
    previewing: Boolean,
    onEndPreview: () -> Unit,
) {
    DismissOnBack(enabled = editing, onDismiss = onLeave)
    DismissOnBack(enabled = editing && selection, onDismiss = onClearSelection)
    DismissOnBack(enabled = editing && slotMenu, onDismiss = onCloseSlotMenu)
    DismissOnBack(enabled = editing && previewing, onDismiss = onEndPreview)
}
