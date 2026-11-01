package hivens.ui.nx

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun NxVerticalScrollbar(state: ScrollState, revealed: Boolean, modifier: Modifier) = Unit

@Composable
actual fun NxVerticalScrollbar(state: LazyListState, revealed: Boolean, modifier: Modifier) = Unit

@Composable
actual fun NxHorizontalScrollbar(state: ScrollState, revealed: Boolean, modifier: Modifier) = Unit
