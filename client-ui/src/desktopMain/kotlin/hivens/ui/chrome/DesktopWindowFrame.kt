package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import hivens.window.WindowHost

/**
 * The frame of an undecorated desktop window: caption buttons and drag are drawn and handled here.
 * Each of them stands down where the window manager does not allow its action, see [WindowCapabilities].
 *
 * Where the system's own frame moves the window ([WindowHost.nativeFrame]) the drag area only tells it
 * where the caption is, and the elements inside it that keep their own clicks.
 */
class DesktopWindowFrame(
    private val window: ComposeWindow?,
    private val host: WindowHost,
    private val capabilities: State<WindowCapabilities>,
    private val onClose: () -> Unit,
) : WindowFrame {

    override fun toggleMaximize() {
        if (capabilities.value.maximize) host.toggleMaximize()
    }

    @Composable
    override fun Controls() = WindowControls(host, capabilities.value, onClose)

    override fun Modifier.dragArea(): Modifier {
        if (host.nativeFrame) return captionRegion(host.captions)
        val allowed = capabilities.value
        return windowDragArea(
            window = window,
            host = host,
            canMove = allowed.move,
            onDoubleClick = if (allowed.maximize) host::toggleMaximize else null,
        )
    }

    override fun Modifier.dragExclusion(): Modifier = if (host.nativeFrame) captionExclusion(host.captions) else this
}
