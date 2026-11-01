package hivens.launcher.network

import hivens.core.security.SslBypassStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Carries "this host's certificate was refused" from whichever request hit it to
 * the shell, which owns the prompt.
 *
 * The decision used to live inside the login form, and the form was the only place
 * that could ask for it. Every read of the SmartyCraft host goes through the client
 * the [SslBypassStore] picks, so before a login had granted the bypass, reads that
 * need no session at all -- the server roster, the news the site publishes publicly
 * -- failed at the transport and their surfaces showed nothing. Signing in was the
 * way to get news, which is not what either of them is about.
 *
 * One slot at a time: the host, plus what to retry once the user accepts. A raise for
 * a host that is already trusted, already pending, or that the user has already turned
 * down is dropped -- background reads retry on their own and must not stack dialogs
 * or nag.
 */
class CertificateTrustGate(private val bypasses: SslBypassStore) {

    /**
     * @param host the host whose certificate was refused.
     * @param onGranted what to run once the user accepts. Null for a background read:
     *        those surfaces re-fetch on the bypass flip by themselves.
     */
    data class Request(val host: String, val onGranted: (() -> Unit)?)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * Hosts the user has turned down. Asking again on the next background read would
     * be nagging about a decision already made; an explicit retry -- a login the user
     * just submitted -- is a fresh question and clears it.
     */
    private val declined = mutableSetOf<String>()

    @Synchronized
    fun request(host: String, onGranted: (() -> Unit)? = null) {
        if (host.isBlank() || bypasses.isBypassed(host)) return
        val explicit = onGranted != null
        if (!explicit && host in declined) return
        val current = _pending.value
        // Only an explicit request may take a slot that is already occupied, and only
        // from a background one: it is the raise with somewhere to go once the answer
        // arrives, and the user is standing in front of it.
        if (current != null && !(explicit && current.onGranted == null)) return
        if (explicit) declined -= host
        _pending.value = Request(host, onGranted)
    }

    /** The user accepted until [until]: grant, close the slot, run whatever was waiting. */
    @Synchronized
    fun accept(until: Instant) {
        val request = _pending.value ?: return
        _pending.value = null
        bypasses.grant(request.host, until)
        request.onGranted?.invoke()
    }

    /** The user said no. Nothing asks again about this host until the launcher restarts. */
    @Synchronized
    fun dismiss() {
        val request = _pending.value ?: return
        _pending.value = null
        declined += request.host
    }
}
