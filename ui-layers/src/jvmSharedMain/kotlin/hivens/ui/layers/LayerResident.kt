package hivens.ui.layers

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * One thing drawn above a window's own content: a popup, a sheet, the editor, the
 * debug overlay. Made by [Layer] and known to the window's [WindowLayers] while it
 * is shown.
 *
 * Opaque outside this module. Code that pushes onto the [DismissStack] by hand
 * passes the one [LocalResident] names, so its entry is ordered with the layer it
 * was opened from.
 */
class LayerResident internal constructor(
    /** The layer this one was opened from, which it always draws above. Null for one opened from the window's own content. */
    internal val parent: LayerResident?,
) {
    /** When it last appeared, on the window's clock. Taken on every visible edge, so a layer shown again comes back on top. */
    internal var stamp: Long = 0L

    internal var modal by mutableStateOf(false)
    internal var staysUsable by mutableStateOf(false)
    internal var pinnedTop by mutableStateOf(false)
    internal var focusable by mutableStateOf(false)
    /** Whether a focusable layer leaves the keyboard in a text field that holds it as the layer appears. */
    internal var yieldsToField = false
    /** What a press outside a modal asks for, null when such a press closes nothing. */
    internal var onOutsidePress by mutableStateOf<(() -> Unit)?>(null)
    internal var context by mutableStateOf<CompositionLocalContext?>(null)
    internal var content by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)

    /** The focus target at the root of a focusable layer. */
    internal val focus = FocusRequester()

    /** Whether the keyboard focus is anywhere inside this layer. */
    internal var holdsFocus = false

    /** Where the caller wants the focus to go when the layer leaves, ahead of everything below. */
    internal var returnFocus: FocusRequester? = null

    /** The group the focus came from when this layer took it, and goes back to when it leaves. */
    internal var focusReturn: FocusRequester? = null

    /** Whether [focusReturn] noted which of its children had the focus, to give it back to that one. */
    internal var focusReturnToChild = false

    /** The marked control that held the focus when this layer took it, see [layerTrigger]. */
    internal var trigger: LayerTrigger? = null

    /** The layer the focus was taken from, when it was a layer rather than the window's content. */
    internal var takenFrom: LayerResident? = null

    /**
     * Whether the layer is in the order. Its frame can still be composed for a pass
     * after it leaves, and must not move the focus from there.
     */
    internal var shown = false

    /** Whether the layer took the focus away from where it was as it appeared, holding it or not. */
    internal var tookFocus = false

    /** Set while the layer is leaving, so a modal one lets the focus go back out of it. */
    internal var releasing = false
}
