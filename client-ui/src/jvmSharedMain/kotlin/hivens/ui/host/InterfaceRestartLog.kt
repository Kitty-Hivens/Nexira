package hivens.ui.host

import hivens.widget.api.InterfaceKeys
import hivens.widget.api.InterfaceRestarts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The interface's starts over after a crash this run, which the host answers [InterfaceKeys.Restarts] with. Told by
 * the restart loop before the interface is drawn again, so a module reading it when the interface comes back up finds
 * it already counted.
 */
class InterfaceRestartLog {
    private val state = MutableStateFlow(InterfaceRestarts())

    val restarts: StateFlow<InterfaceRestarts> = state.asStateFlow()

    /** The interface is about to start over after a crash, [again] before it had stayed up since the last. */
    fun record(again: Boolean) = state.update { InterfaceRestarts(it.count + 1, again) }
}
