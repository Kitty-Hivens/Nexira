package hivens.ui.nx

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round

/**
 * The menu a right click opens, drawn by the library's own: over a text field, over text that can be
 * selected, and over whatever a foundation `ContextMenuArea` wraps. Foundation decides what is in it and
 * when it opens, and draws one of its own otherwise, square, in the system's font and above every layer.
 */
internal object PointerMenu : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val open = state.status as? ContextMenuState.Status.Open
        val shown = remember { Shown() }
        if (open != null) {
            shown.at = open.rect.center.round()
            shown.items = items()
        }
        val empty = open != null && shown.items.isEmpty()
        // Foundation takes no further right click while the state says open, so a menu with nothing in it is closed.
        LaunchedEffect(empty) { if (empty) state.status = ContextMenuState.Status.Closed }
        NxPointMenu(
            at               = shown.at,
            expanded         = open != null && !empty,
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
        ) {
            for (item in shown.items) {
                NxMenuItem(item.label) {
                    state.status = ContextMenuState.Status.Closed
                    item.onClick()
                }
            }
        }
    }

    /** What the menu last opened with, which it is still drawn with on its way out. */
    private class Shown {
        var at = IntOffset.Zero
        var items = emptyList<ContextMenuItem>()
    }
}
