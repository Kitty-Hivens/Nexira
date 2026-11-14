package hivens.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * A [Layer] that hangs off the layout it is called in, the way a Compose popup does:
 * [position] is asked where [content] goes, given that layout's bounds, the size of
 * the room the window has for it and the content's own size.
 *
 * The anchor is the parent layout of this call, so it is declared inside the box
 * that wraps its trigger. The room is the window inside the system bars, the
 * display cutout and the software keyboard on every side, and the bounds are
 * measured from its corner, as a Compose popup measures them against the window's
 * insets. A menu flipping up near the top stays below the status bar, and one over
 * a field flips above it rather than under the keyboard. On the desktop the room is
 * the whole window. [content] is measured against the room and placed once the
 * anchor has been laid out.
 *
 * [modal], [focusable], [onDismissRequest], [dismissOnOutsidePress] and [returnFocus]
 * mean what they mean on [Layer]. A menu is modal: a press outside it closes it and
 * goes no further, and it takes the keyboard. A tooltip is neither.
 *
 * [yieldsToField] is for a list hung off the text field it filters: appearing while a
 * text field holds the keyboard, the layer leaves it there, so typing goes on into
 * the field, and the field is not told it was focused again when the list goes.
 *
 * Outside a [LayerHost] there is no layer to go to, and it opens as a Compose popup
 * with the same [position] instead, so a window that has no host yet still shows it.
 */
@Composable
fun AnchoredLayer(
    visible: Boolean,
    position: PopupPositionProvider,
    modal: Boolean = false,
    focusable: Boolean = modal,
    onDismissRequest: (() -> Unit)? = null,
    dismissOnOutsidePress: Boolean = true,
    returnFocus: FocusRequester? = null,
    yieldsToField: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (LocalWindowLayers.current == null) {
        if (visible) {
            Popup(
                popupPositionProvider = position,
                onDismissRequest = onDismissRequest,
                properties = PopupProperties(focusable = focusable, dismissOnClickOutside = dismissOnOutsidePress),
                content = content,
            )
        }
        return
    }
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    // Where a Compose popup finds its anchor too: the layout this call sits in.
    Layout(
        content = {},
        modifier = Modifier.onGloballyPositioned { coordinates ->
            val parent = coordinates.parentLayoutCoordinates ?: return@onGloballyPositioned
            val bounds = IntRect(parent.positionInWindow().round(), parent.size)
            if (bounds != anchor) anchor = bounds
        },
    ) { _, _ -> layout(0, 0) {} }
    ShowLayer(
        visible = visible,
        modal = modal,
        staysUsable = false,
        pinnedTop = false,
        focusable = focusable,
        onDismissRequest = onDismissRequest,
        dismissOnOutsidePress = dismissOnOutsidePress,
        returnFocus = returnFocus,
        yieldsToField = yieldsToField,
        liftsOverKeyboard = false,
    ) {
        Anchored({ anchor }, position, body = modal, content)
    }
}

@Composable
private fun BoxScope.Anchored(
    anchor: () -> IntRect?,
    position: PopupPositionProvider,
    /** Whether the placed content is a modal's body, which a press outside it closes. */
    body: Boolean,
    content: @Composable () -> Unit,
) {
    val insets = WindowInsets.safeDrawing
    // The same box either way, so a layer that stops being modal while it animates
    // out keeps its content rather than composing it afresh.
    val placed: @Composable () -> Unit = { Box(if (body) Modifier.layerBody() else Modifier) { content() } }
    Layout(placed, Modifier.matchParentSize()) { measurables, constraints ->
        val left = insets.getLeft(this, layoutDirection)
        val top = insets.getTop(this)
        val room = IntSize(
            (constraints.maxWidth - left - insets.getRight(this, layoutDirection)).coerceAtLeast(0),
            (constraints.maxHeight - top - insets.getBottom(this)).coerceAtLeast(0),
        )
        val placeables = measurables.map { it.measure(Constraints(maxWidth = room.width, maxHeight = room.height)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Read while placing, so the content follows the anchor when it moves
            // without being measured again.
            val bounds = anchor() ?: return@layout
            val origin = (coordinates?.positionInWindow() ?: Offset.Zero).round()
            val inRoom = bounds.translate(-left, -top)
            for (placeable in placeables) {
                val at = position.calculatePosition(inRoom, room, layoutDirection, IntSize(placeable.width, placeable.height))
                placeable.place(at.x + left - origin.x, at.y + top - origin.y)
            }
        }
    }
}
