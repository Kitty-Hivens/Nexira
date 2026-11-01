package hivens.ui.notifications

import hivens.core.data.SessionData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries "this launch needs a fresh second factor" from the launch driver to the
 * shell, which owns the prompt.
 *
 * A second-factor account gets a session minted for the launch it is about to make:
 * SmartyCraft invalidates a session on any later login, from anywhere, so a stored
 * one cannot be vouched for and the player would learn it only when the server
 * refuses the join. One code per launch buys a token that is known good at spawn.
 *
 * The driver knows which target was being launched and how to start it again; the
 * shell knows how to ask for a code. Neither can reach the other directly, hence
 * this one-slot handoff: [request] parks what is needed, the shell collects
 * [pending], and [resume] carries the fresh session back to the relaunch.
 */
class TwoFactorLaunchGate {

    /**
     * @param label what is being launched, for the prompt to name.
     * @param serverId the SmartyCraft server this launch signs in for. The stored
     *        credentials do not carry one -- the credential store rebuilds sessions
     *        without it -- and a login against a blank server mints a token for the
     *        wrong place, so the target has to supply it.
     * @param relaunch restarts that launch with the session the code produced.
     */
    data class Request(
        val label: String,
        val serverId: String,
        val relaunch: (SessionData) -> Unit,
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Latest request wins: a second launch supersedes a prompt nobody answered. */
    fun request(label: String, serverId: String, relaunch: (SessionData) -> Unit) {
        _pending.value = Request(label, serverId, relaunch)
    }

    /** The user answered: hand the session to the waiting relaunch and close the slot. */
    fun resume(session: SessionData) {
        val pending = _pending.getAndUpdate { null } ?: return
        pending.relaunch(session)
    }

    /** The user dismissed the prompt; the launch simply does not happen. */
    fun cancel() {
        _pending.value = null
    }
}

private fun <T> MutableStateFlow<T>.getAndUpdate(transform: (T) -> T): T {
    while (true) {
        val current = value
        if (compareAndSet(current, transform(current))) return current
    }
}
