package hivens.core.security

import kotlinx.serialization.Serializable

/**
 * One entry in the "trusted-despite-broken-cert" host set. Intentionally
 * narrow: [host] + [expiresAt] only -- no fingerprints, certificate
 * chains, or per-port granularity. The use case is a transient cert
 * outage on one host with user-accepted, bounded-time risk; public-key
 * pinning belongs elsewhere.
 *
 * [expiresAt] is ISO-8601 so on-disk JSON is debuggable by eye; readers
 * compare against `Instant.now()` and treat past timestamps as absent.
 * Expiry rather than permanent flag matches actual user intent ("trust
 * THIS host until the issue resolves"), not "disable TLS for everything
 * for the session".
 */
@Serializable
data class SslBypassEntry(
    val host: String,
    /** ISO-8601 timestamp (e.g. "2026-06-12T08:30:00Z"). */
    val expiresAt: String,
)
