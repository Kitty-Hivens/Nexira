package hivens.ui.nx

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The auto-hiding bar for a scrollable that is described by its state.
 *
 * A bar is a desktop idea: a pointer needs something to grab, and a finger scrolls
 * the content itself. So the desktop draws [NxVerticalScrollbar] over the state's
 * adapter and a touch host draws nothing, which keeps the menus that call this free
 * of the platform's scrollbar types.
 */
@Composable
expect fun NxVerticalScrollbar(state: ScrollState, revealed: Boolean, modifier: Modifier = Modifier)

@Composable
expect fun NxVerticalScrollbar(state: LazyListState, revealed: Boolean, modifier: Modifier = Modifier)

/** [NxVerticalScrollbar] laid along the bottom edge, for content that scrolls sideways. */
@Composable
expect fun NxHorizontalScrollbar(state: ScrollState, revealed: Boolean, modifier: Modifier = Modifier)
