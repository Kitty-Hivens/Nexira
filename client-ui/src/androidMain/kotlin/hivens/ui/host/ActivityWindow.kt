package hivens.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import hivens.ui.chrome.WindowCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The phone's one window, the Activity, as the host sees it. Its size and focus
 * are what Compose reads off the Activity's window, its frame and its lifetime are
 * the system's, so it takes no commands: nothing here can maximise, minimise,
 * raise or close it.
 */
@Composable
fun rememberActivityWindow(): WindowBackend {
    val info = LocalWindowInfo.current
    val density = LocalDensity.current
    // Read once here as well as followed below, so the first frame already has the
    // window's own size and focus rather than an empty one.
    val backend = remember { ActivityWindow(stateOf(info, density.density)) }
    LaunchedEffect(info, density) {
        snapshotFlow { stateOf(info, density.density) }.collect { backend.update(it) }
    }
    return backend
}

private fun stateOf(info: WindowInfo, density: Float) = HostWindowState(
    width = (info.containerSize.width / density).roundToInt(),
    height = (info.containerSize.height / density).roundToInt(),
    maximized = false,
    minimized = false,
    focused = info.isWindowFocused,
    allowed = WindowCapabilities.Nothing,
)

private class ActivityWindow(initial: HostWindowState) : WindowBackend {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<HostWindowState> = _state.asStateFlow()
    override val commands: Set<WindowCommand> = emptySet()

    fun update(next: HostWindowState) {
        _state.value = next
    }

    override fun run(command: WindowCommand) = Unit

    override fun raise() = Unit

    override fun close() = Unit
}
