package hivens.ui.theme

import androidx.compose.runtime.Composable

@Composable
internal actual fun ThemedContextMenu(colours: SchemeColours, content: @Composable () -> Unit) = content()
