package hivens.ui.logic

import hivens.core.launch.LaunchHandle
import hivens.core.launch.LaunchState

/** What the shell does with its own window around a game session. */
enum class PostLaunchMove {
    /** Leave the window where it is. */
    Stay,

    /** Take the window off screen; the tray icon is the way back. */
    HideToTray,

    /** Iconify it -- what getting out of the way means with no tray to hide into. */
    Minimize,

    /** Raise the window this gate iconified. */
    Restore,
}

/**
 * Decides what happens to the launcher window when the game comes up, once per
 * session.
 *
 * The rule belongs to the shell rather than to a screen: every launch path --
 * the classic dashboard, the Library, a pack page, a relaunch driven from a
 * notification -- lands in the same [LaunchState], while the widget that used to
 * own the rule is composed for one of them. Kept out of composition so the
 * choice can be exercised without one.
 *
 * Sessions are told apart by their [LaunchHandle] rather than by counting
 * transitions through the state machine: a [kotlinx.coroutines.flow.StateFlow]
 * is conflated, so a state the shell never observes must not be what stands
 * between two sessions. [runningAtMount] is the handle of a session already
 * under way when the gate is built -- a shell restarted after a UI crash re-runs
 * its effects against the state it finds, and arriving mid-session is not the
 * transition the setting speaks about.
 */
class PostLaunchGate(runningAtMount: LaunchHandle? = null) {

    /** The session this gate has already had its say about. */
    private var handled: LaunchHandle? = runningAtMount

    /** Whether the window sits iconified because this gate put it there. */
    private var iconified = false

    /**
     * Feeds the gate the launch state just observed. [hideAfterStart] is the
     * user's setting, [trayReady] whether there is a tray icon to hide into and
     * [windowMinimized] where the window stands right now -- all read when the
     * game starts rather than at construction, since any of them can change
     * while the launcher is open.
     */
    fun onState(
        state: LaunchState,
        hideAfterStart: Boolean,
        trayReady: Boolean,
        windowMinimized: Boolean,
    ): PostLaunchMove {
        if (state !is LaunchState.GameRunning) {
            // A failed launch has something to read, and the window this gate
            // iconified is where it would be read. Anything else, including a
            // window the user minimized themselves, is not ours to raise: the
            // failure is in the notification centre either way, and taking the
            // desktop back from them costs more than a banner they missed.
            val raise = state is LaunchState.Error && iconified
            iconified = false
            return if (raise) PostLaunchMove.Restore else PostLaunchMove.Stay
        }
        if (state.handle === handled) return PostLaunchMove.Stay
        handled = state.handle
        if (!hideAfterStart) return PostLaunchMove.Stay
        // Hiding with no tray to hide into leaves nothing to click to get the
        // launcher back, so the window goes down to the taskbar instead.
        val move = if (trayReady) PostLaunchMove.HideToTray else PostLaunchMove.Minimize
        iconified = move == PostLaunchMove.Minimize && !windowMinimized
        return move
    }
}
