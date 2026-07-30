package hivens.ui.navigation

import hivens.ui.Screen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Navigation requests from outside the composition -- a notification action, a
 * background driver -- into the shell's back stack. AppRoot collects [requests]
 * next to the side-mouse-button listener; anything holding this singleton can
 * ask for a screen without a reference into the composition (the node -> state
 * -> subscriber arrow, never node -> node).
 */
class NavRequests {
    private val flow = MutableSharedFlow<Screen>(extraBufferCapacity = 16)
    val requests: SharedFlow<Screen> = flow

    fun open(screen: Screen) {
        flow.tryEmit(screen)
    }
}
