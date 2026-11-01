package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState

/**
 * The frame of an undecorated desktop window: caption buttons and drag are drawn and handled here.
 * Each of them stands down where the window manager does not allow its action, see [WindowCapabilities].
 */
class DesktopWindowFrame(
    private val state: WindowState,
    private val window: ComposeWindow?,
    private val maximizer: WindowMaximizer,
    private val capabilities: WindowCapabilitiesSource,
    private val onClose: () -> Unit,
) : WindowFrame {

    override fun toggleMaximize() {
        if (capabilities.current.maximize) maximizer.toggle()
    }

    @Composable
    override fun Controls() = WindowControls(state, maximizer, capabilities.current, onClose)

    override fun Modifier.dragArea(): Modifier {
        val allowed = capabilities.current
        return windowDragArea(
            window = window,
            maximizer = maximizer,
            canMove = allowed.move,
            onDoubleClick = if (allowed.maximize) maximizer::toggle else null,
        )
    }
}
