package hivens.ui.layers

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    internal var onDismissRequest by mutableStateOf<(() -> Unit)?>(null)
    internal var context by mutableStateOf<CompositionLocalContext?>(null)
    internal var content by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)
}
