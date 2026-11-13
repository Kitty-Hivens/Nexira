package hivens.ui.navigation

import hivens.ui.Screen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference

/**
 * Navigation requests from inside a window but outside its back stack (a pack
 * card, a link in a page, a dialog) into the back stack of that same window.
 * AppRoot collects [requests] next to the side-mouse-button listener. Anything
 * holding this can ask for a screen without a reference into the composition (the
 * node -> state -> subscriber arrow, never node -> node).
 *
 * One per window, made by the window's level, so a screen opened in a second
 * window navigates that window and not the first.
 */
class NavRequests {
    private val flow = MutableSharedFlow<Screen>(extraBufferCapacity = 16)
    val requests: SharedFlow<Screen> = flow

    /** How many back stacks are listening. A request with nobody listening goes nowhere. */
    val readers: StateFlow<Int> get() = flow.subscriptionCount

    fun open(screen: Screen) {
        flow.tryEmit(screen)
    }
}

/**
 * Navigation requests from outside every window (a notification action, a
 * background driver), which land in the main window and raise it. One for the app:
 * a toast has no window of its own to navigate, and the main window is where the
 * person expects the launcher to answer.
 *
 * Kept until a main window takes them. A shell restarting after a crash, or one on
 * the migration screen with no history to navigate, would otherwise lose a request
 * made while it had none. The newest [PENDING] are kept and older ones dropped.
 */
class MainWindowRequests {
    private val pending = Channel<Screen>(PENDING, BufferOverflow.DROP_OLDEST)

    // Taken off the queue and not handed over yet: the next taker starts with it, so
    // a shell that went away in between does not lose it.
    private val inHand = AtomicReference<Screen?>(null)

    fun open(screen: Screen) {
        val _ = pending.trySend(screen)
    }

    /**
     * Hands every request to [window], the main window's own requests, once its back
     * stack listens, and calls [raise] after each one is handed over. For the main
     * window's shell to run for as long as it is open.
     */
    suspend fun deliverTo(window: NavRequests, raise: () -> Unit): Nothing {
        while (true) {
            val screen = inHand.get() ?: pending.receive().also { inHand.set(it) }
            val _ = window.readers.first { it > 0 }
            window.open(screen)
            inHand.compareAndSet(screen, null)
            raise()
        }
    }

    private companion object {
        const val PENDING = 16
    }
}
