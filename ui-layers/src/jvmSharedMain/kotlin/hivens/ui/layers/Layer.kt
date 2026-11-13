package hivens.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Draws [content] above the window's own content while [visible], at full window
 * size, in the window's [LayerHost].
 *
 * Where it stands: the most recently shown layer is on top, counted from the moment
 * [visible] turns true rather than from when this call entered the composition. A
 * layer opened from inside another is that one's child: it draws right above its
 * parent, and showing it brings the family up with it (see [planOf]). [pinnedTop]
 * puts it above every layer that is not, for diagnostics. Nothing is raised by a
 * click.
 *
 * Input: [content] takes the pointer only where it has something to take it with.
 * A [modal] layer blocks input to every layer under it and to the window's content,
 * except to layers that are [staysUsable], which keep working under it.
 *
 * Keyboard: a [focusable] layer takes the focus as it appears, unless something in
 * it has already asked for it, so keys and Escape go through it rather than to what
 * is under it. A [modal] one keeps the focus inside while it is the topmost modal.
 * When it goes, the focus goes back to where it was taken from. A layer that is not
 * focusable never takes the focus, which is what a tooltip wants.
 *
 * [onDismissRequest] makes the layer closable: Escape and back reach it through the
 * window's [DismissStack] like anything else open, and a [modal] one also hears a
 * press outside it unless [dismissOnOutsidePress] is false.
 *
 * [returnFocus] is where the focus goes when the layer leaves, for a trigger that
 * knows it should have it back. Without it the layer gives the focus back to what
 * had it when the layer took it, or to the place it was opened from when that is
 * gone, and to nothing when nothing had it.
 *
 * [content] is composed with the composition locals of this call site, so it keeps
 * the theme, the strings and the scope of where it was opened. [BoxScope] places
 * it in the window.
 *
 * Outside a [LayerHost] there is no order to stand in. A [modal] layer then opens
 * as a Compose popup over the whole window, which still blocks what is under it and
 * hears Escape and back, and a layer that is not modal is drawn in place.
 */
@Composable
fun Layer(
    visible: Boolean,
    modal: Boolean = false,
    staysUsable: Boolean = false,
    pinnedTop: Boolean = false,
    focusable: Boolean = modal,
    onDismissRequest: (() -> Unit)? = null,
    dismissOnOutsidePress: Boolean = true,
    returnFocus: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) = ShowLayer(
    visible, modal, staysUsable, pinnedTop, focusable, onDismissRequest, dismissOnOutsidePress,
    returnFocus, yieldsToField = false, content,
)

/** [Layer], with [yieldsToField] for the layers [AnchoredLayer] places. */
@Composable
internal fun ShowLayer(
    visible: Boolean,
    modal: Boolean,
    staysUsable: Boolean,
    pinnedTop: Boolean,
    focusable: Boolean,
    onDismissRequest: (() -> Unit)?,
    dismissOnOutsidePress: Boolean,
    returnFocus: FocusRequester?,
    yieldsToField: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val layers = LocalWindowLayers.current
    if (layers == null) {
        if (visible) {
            if (modal) WindowPopup(focusable, onDismissRequest, dismissOnOutsidePress, content) else Box(content = content)
        }
        return
    }
    val parent = LocalResident.current
    val resident = remember(layers, parent) { LayerResident(parent) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val requestDismiss = remember(resident) {
        {
            val request = dismiss
            if (request != null) request()
        }
    }
    val dismissible = onDismissRequest != null

    // Written while composing. The host reads them in a scope after the window's own
    // content, so a change made from there lands in the same pass. A call site inside
    // another layer is composed in that scope, and a flag it changes while the layer
    // is shown lands a pass later. A layer joins the order on its visible edge, after
    // composition, so it never joins with the flags it had before.
    resident.modal = modal
    resident.staysUsable = staysUsable
    resident.pinnedTop = pinnedTop
    resident.focusable = focusable
    resident.yieldsToField = yieldsToField
    resident.returnFocus = returnFocus
    resident.onOutsidePress = requestDismiss.takeIf { dismissible && dismissOnOutsidePress }
    resident.context = currentCompositionLocalContext
    resident.content = content

    DisposableEffect(layers, resident, visible) {
        if (visible) layers.show(resident)
        onDispose { if (visible) layers.hide(resident) }
    }
    DisposableEffect(layers, resident, visible && dismissible) {
        val handle = if (visible && dismissible) layers.dismiss.push(resident, requestDismiss) else null
        onDispose { handle?.close() }
    }
}

/**
 * A modal layer in a window with no [LayerHost]: a Compose popup the size of the
 * window, so the content still has the whole window to place itself in.
 */
@Composable
private fun WindowPopup(
    focusable: Boolean,
    onDismissRequest: (() -> Unit)?,
    dismissOnOutsidePress: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Popup(
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = focusable, dismissOnBackPress = onDismissRequest != null),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (onDismissRequest != null && dismissOnOutsidePress) OutsidePress(onDismissRequest)
            Box(Modifier.matchParentSize(), content = content)
        }
    }
}
