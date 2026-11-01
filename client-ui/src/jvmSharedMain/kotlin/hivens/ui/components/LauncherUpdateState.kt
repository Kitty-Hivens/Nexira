package hivens.ui.components

import hivens.core.data.LauncherUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The launcher update the check found, for anything beside the notification that
 * wants to mention it, and the way back to its dialog.
 *
 * [UpdateManager] owns the check and the dialog and is the only writer. It used to
 * hold the answer in its own composition, so the notification was the one place that
 * could know a new build existed, and dismissing the card left nothing on screen to
 * say so. A reader asks for the dialog through [requestDetails] rather than drawing
 * one of its own, so there is still one dialog and one install path.
 */
class LauncherUpdateState {
    private val current = MutableStateFlow<LauncherUpdate?>(null)
    val available: StateFlow<LauncherUpdate?> = current.asStateFlow()

    private val details = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val detailsRequests: SharedFlow<Unit> = details

    fun publish(update: LauncherUpdate?) {
        current.value = update
    }

    fun requestDetails() {
        details.tryEmit(Unit)
    }
}
