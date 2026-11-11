package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState

/**
 * The frame of an undecorated desktop window: caption buttons and drag are drawn and handled here.
 * Each of them stands down where the window manager does not allow its action, see [WindowCapabilities].
 *
 * With a [nativeFrame] (see [Win32Frame]) the system moves the window: the drag area only tells it
 * where the caption is, through [regions], and the elements inside it that keep their own clicks.
 */
class DesktopWindowFrame(
    private val state: WindowState,
    private val window: ComposeWindow?,
    private val maximizer: WindowMaximizer,
    private val capabilities: WindowCapabilitiesSource,
    private val onClose: () -> Unit,
    private val regions: CaptionRegions? = null,
    private val nativeFrame: Boolean = false,
) : WindowFrame {

    override fun toggleMaximize() {
        if (capabilities.current.maximize) maximizer.toggle()
    }

    @Composable
    override fun Controls() = WindowControls(state, maximizer, capabilities.current, onClose)

    override fun Modifier.dragArea(): Modifier {
        if (nativeFrame) return captionRegion(regions)
        val allowed = capabilities.current
        return windowDragArea(
            window = window,
            maximizer = maximizer,
            canMove = allowed.move,
            onDoubleClick = if (allowed.maximize) maximizer::toggle else null,
        )
    }

    override fun Modifier.dragExclusion(): Modifier = if (nativeFrame) captionExclusion(regions) else this
}
