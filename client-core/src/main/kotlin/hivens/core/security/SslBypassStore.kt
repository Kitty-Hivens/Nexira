package hivens.core.security

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * The set of hosts the user has agreed to reach despite a refused certificate,
 * each grant carrying its own expiry so accepting one host's outage never
 * quietly weakens TLS for another.
 *
 * An interface rather than the object it used to be. The transport picks a
 * client by asking [isBypassed], the shell draws the grants and revokes them,
 * and a certificate refusal turns into a grant from a third place -- all of
 * which reached a JVM-global singleton that a test could only wipe between
 * cases and that nothing could substitute. What decides whether TLS
 * verification is skipped should be something a caller is handed, not
 * something it reaches for.
 */
interface SslBypassStore {

    /**
     * Push-side view of the live grants, so a surface follows them instead of
     * polling for state that changes on the scale of minutes to days.
     *
     * Expiry is not ticked here: an entry stays in the list until the process
     * restarts or the user revokes it. [isBypassed] is what answers whether a
     * grant is still good right now.
     */
    val bypasses: StateFlow<List<SslBypassEntry>>

    /** True when [host] holds a grant that has not expired. */
    fun isBypassed(host: String): Boolean

    /** Grant or refresh [host] until [until], replacing any existing grant for it. */
    fun grant(host: String, until: Instant)

    /** Revoke any grant for [host]. Idempotent. */
    fun revoke(host: String)
}
