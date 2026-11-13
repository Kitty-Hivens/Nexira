package hivens.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

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
 * [onDismissRequest] makes the layer closable: Escape and back reach it through the
 * window's [DismissStack] like anything else open, and a [modal] one also hears a
 * press outside it.
 *
 * [content] is composed with the composition locals of this call site, so it keeps
 * the theme, the strings and the scope of where it was opened. [BoxScope] places
 * it in the window. Outside a [LayerHost], with nowhere above to go, it is drawn in
 * place.
 */
@Composable
fun Layer(
    visible: Boolean,
    modal: Boolean = false,
    staysUsable: Boolean = false,
    pinnedTop: Boolean = false,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val layers = LocalWindowLayers.current
    if (layers == null) {
        if (visible) Box(content = content)
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
    resident.onDismissRequest = requestDismiss.takeIf { dismissible }
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
