package hivens.core.security

import kotlinx.serialization.Serializable

/**
 * One entry in the launcher's set of "trusted-despite-broken-cert" hosts.
 *
 * The model deliberately keeps **host** and **expiry** as the only fields:
 * we don't store fingerprints, certificate chains, or per-port granularity
 * because the use case is narrow — a transient cert outage on the
 * `smartycraft.ru` channel where the user explicitly accepted the risk
 * for a bounded time window. A real public-key-pinning model belongs
 * elsewhere (Vault sub-pillar continuation, not this chunk).
 *
 * `expiresAt` is serialised as an **ISO-8601 string** so the on-disk JSON
 * is debuggable by eye. Code reading the entry compares against
 * `Instant.now()` and treats anything in the past as effectively absent.
 *
 * Why expiry instead of permanent: the historical
 * `NetworkState.sslBypassEnabled: Boolean` meant "once user accepted, TLS
 * verification is off for every HTTPS call until process exit". That's
 * a hidden expansion of trust: the user agreed to one cert outage and
 * unintentionally granted "trust all hosts for hours". Scoped + expiring
 * matches the actual user intent ("trust THIS host until issue resolves").
 */
@Serializable
data class SslBypassEntry(
    val host: String,
    /** ISO-8601 timestamp ("2026-06-12T08:30:00Z" form). */
    val expiresAt: String,
)
