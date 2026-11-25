package hivens.ui.theme

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import hivens.ui.nx.PointerMenu

@Composable
internal actual fun ThemedContextMenu(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContextMenuRepresentation provides PointerMenu, content = content)
