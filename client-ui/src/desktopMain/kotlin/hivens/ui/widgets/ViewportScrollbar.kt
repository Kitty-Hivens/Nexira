package hivens.ui.widgets

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import hivens.ui.nx.NxHorizontalScrollbar
import hivens.ui.nx.NxVerticalScrollbar
import hivens.widget.api.ViewportScrollbar

/**
 * The bar a scrolling slot draws: the same auto-hiding bar every other list in the
 * launcher has, on the far edge of the axis the slot moves along.
 */
val NxViewportScrollbar: ViewportScrollbar = { state, horizontal, revealed ->
    val adapter = rememberScrollbarAdapter(state)
    if (horizontal) {
        NxHorizontalScrollbar(adapter, revealed, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    } else {
        NxVerticalScrollbar(adapter, revealed, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
