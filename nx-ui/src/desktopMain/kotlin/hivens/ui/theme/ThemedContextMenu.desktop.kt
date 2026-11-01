package hivens.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp

@Composable
internal actual fun ThemedContextMenu(colours: SchemeColours, content: @Composable () -> Unit) {
    val contextMenu = remember(colours) {
        val top = colours.step(colours.topStep)
        DefaultContextMenuRepresentation(
            backgroundColor = top,
            textColor       = colours.inkMain(top),
            itemHoverColor  = lerp(top, colours.lead, 0.14f),
        )
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides contextMenu) {
        content()
    }
}
