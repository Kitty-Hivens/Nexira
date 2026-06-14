package hivens.ui.notifications

import kotlinx.serialization.Serializable

/**
 * Durable projection of a [NotificationEvent] for the on-disk message log
 * (read back by the history widget). Drops the live-only fields: `actions`
 * (their `onClick` lambdas cannot be serialized) and `progress` (a past event
 * has no live bar). [Severity]/[Kind] are plain enums -- kotlinx serializes
 * them by name, so no annotation is needed on them.
 */
@Serializable
data class PersistedNotification(
    val sourceKey: String,
    val sender: String,
    val iconUrl: String? = null,
    // Vector-glyph fallback for sources without an `iconUrl`; serialized by
    // name. Default null keeps old archive files decoding unchanged.
    val glyph: NotifGlyph? = null,
    val severity: Severity,
    val kind: Kind,
    val title: String,
    val body: String? = null,
    val createdAtEpoch: Long,
)
