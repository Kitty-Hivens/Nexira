package hivens.accounts

import hivens.core.data.SessionData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries "this launch needs a fresh second factor" from whoever runs the launch to
 * the accounts module, which asks the person for the code.
 *
 * A second-factor account gets a session minted for the launch it is about to make:
 * SmartyCraft invalidates a session on any later login, from anywhere, so a stored
 * one cannot be vouched for and the player would learn it only when the server
 * refuses the join. One code per launch buys a token that is known good at spawn.
 *
 * The launch side knows which target was being launched and how to start it again.
 * The accounts module knows how to sign in and ask for a code. Neither can reach the
 * other directly, hence this one-slot handoff: [request] parks what is needed, the
 * module collects [pending], and [resume] carries the fresh session back to the
 * relaunch. The host makes the one the two share.
 */
class TwoFactorLaunchGate {

    /**
     * @param label what is being launched, for the question to name.
     * @param provider the provider whose stored account the launch signs in with.
     * @param serverId the server this launch signs in for. The stored
     *        credentials do not carry one, since the credential store rebuilds
     *        sessions without it, and a login against a blank server mints a token for
     *        the wrong place, so the target has to supply it.
     * @param relaunch restarts that launch with the session the code produced.
     */
    data class Request(
        val label: String,
        val provider: String,
        val serverId: String,
        val relaunch: (SessionData) -> Unit,
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Latest request wins: a second launch supersedes a question nobody answered. */
    fun request(label: String, provider: String, serverId: String, relaunch: (SessionData) -> Unit) {
        _pending.value = Request(label, provider, serverId, relaunch)
    }

    /** The person answered: hand the session to the waiting relaunch and close the slot. */
    fun resume(session: SessionData) {
        val pending = _pending.getAndUpdate { null } ?: return
        pending.relaunch(session)
    }

    /** The person gave it up, or nothing can answer it: the launch simply does not happen. */
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
