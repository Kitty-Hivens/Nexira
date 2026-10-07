package hivens.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxHorizontalScrollbar
import hivens.ui.nx.NxVerticalScrollbar
import hivens.widget.api.MapControls
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

/**
 * The way back on a map: a button in its corner once the view has been moved off
 * what is on it, and nothing while the content is in view. A plane with no edges
 * has no scrollbar to say where the content went, so this says it instead.
 */
val NxMapControls: MapControls = { away, goHome ->
    AnimatedVisibility(
        visible  = away,
        enter    = fadeIn(),
        exit     = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        NxButton(
            label   = LocalStrings.current.mapGoHome,
            onClick = goHome,
            icon    = NxIcon.Dashboard,
            compact = true,
        )
    }
}
