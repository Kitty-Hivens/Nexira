package hivens.ui.host

import hivens.ui.chrome.toCapabilities
import hivens.window.WindowActions
import hivens.window.WindowHost
import hivens.window.WindowSize
import hivens.window.WindowStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.awt.EventQueue

/**
 * A desktop window as the host sees it: what its [WindowHost] reads from the
 * system, and its commands passed to it. [host] is null until the window has a
 * native peer to attach to, and the window reads as [HostWindowState.Unknown] and
 * takes no command until then. [onClose] is the window's own close path, and
 * [onRaise] how it is brought forward, which for the main window also takes it
 * back out of the tray.
 *
 * The commands run on the AWT event thread, where [WindowHost] wants them, whoever
 * fires them. One the window manager does not allow does nothing, the same as the
 * caption button that stands down for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopWindowBackend(
    private val host: StateFlow<WindowHost?>,
    scope: CoroutineScope,
    override val commands: Set<WindowCommand>,
    private val onClose: () -> Unit,
    private val onRaise: () -> Unit = { host.value?.raise() },
) : WindowBackend {

    override val state: StateFlow<HostWindowState> =
        host.flatMapLatest { attached ->
            if (attached == null) {
                flowOf(HostWindowState.Unknown)
            } else {
                combine(attached.status, attached.size, attached.focused, attached.actions, ::stateOf)
            }
        }.stateIn(scope, SharingStarted.Eagerly, host.value?.let(::currentOf) ?: HostWindowState.Unknown)

    override fun run(command: WindowCommand) = onEventThread {
        if (command == WindowCommand.Close) {
            onClose()
            return@onEventThread
        }
        val attached = host.value ?: return@onEventThread
        val allowed = attached.actions.value
        when (command) {
            WindowCommand.Maximize -> if (allowed.maximize && attached.maximizeSupported) attached.maximize()
            WindowCommand.Minimize -> if (allowed.minimize) attached.minimize()
            WindowCommand.Restore -> {
                attached.unminimize()
                if (allowed.maximize && attached.status.value.maximized) attached.restore()
            }
            WindowCommand.Close -> Unit
        }
    }

    override fun raise() = onEventThread(onRaise)

    override fun close() = onEventThread(onClose)

    private fun onEventThread(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater(block)
    }
}

private fun currentOf(host: WindowHost) = stateOf(host.status.value, host.size.value, host.focused.value, host.actions.value)

private fun stateOf(status: WindowStatus, size: WindowSize, focused: Boolean, actions: WindowActions) = HostWindowState(
    width = size.width,
    height = size.height,
    maximized = status.maximized,
    minimized = status.minimized,
    focused = focused,
    allowed = actions.toCapabilities(),
)
