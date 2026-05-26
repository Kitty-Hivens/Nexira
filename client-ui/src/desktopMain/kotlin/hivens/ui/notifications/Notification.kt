package hivens.ui.notifications

import java.time.Instant
import java.util.UUID

/**
 * Visual hint for a notification group's leading avatar slot. Stays
 * abstract -- the renderer maps these to a Compose Painter at draw
 * time so non-UI code (drivers, tests) does not depend on Coil /
 * resource generation.
 */
sealed class AvatarSource {
    /** Remote URL fetched + cached via Coil. Pack icons go here. */
    data class Url(val url: String) : AvatarSource()

    /**
     * Reference to a known iconography slot (e.g. `mirror`, `update`,
     * `system`). The renderer holds the registry of which Compose
     * Painter to render per slot.
     */
    data class Glyph(val name: String) : AvatarSource()

    /** Fallback when no specific avatar fits. Renders a neutral generic shape. */
    data object Generic : AvatarSource()
}

/**
 * One-click action attached to a notification event.
 *
 * `id` lets a future test or puppet observer assert "this action
 * exists" without coupling to label text (which is i18n'd). Common
 * ids: `show_console`, `retry`, `dismiss`, `view_details`.
 */
data class NotifAction(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * One event inside a [NotificationGroup]. A group accumulates events
 * over time as the underlying operation progresses; the latest event
 * is what the collapsed card displays, and the chevron expands to
 * show the history.
 *
 * Progress semantics on [progress]:
 *  - null  -> no progress bar, plain text card
 *  - 0..1  -> determinate fraction, fills the accent strip
 *  - NaN   -> indeterminate, accent strip animates
 *
 * Mirrors the sentinel contract from
 * [hivens.ui.easter.AprilFoolsProgress.wrap] so a driver can pipe its
 * download tick straight through.
 */
data class NotificationEvent(
    val id: UUID,
    val severity: Severity,
    val title: String,
    val body: String? = null,
    val progress: Float? = null,
    val actions: List<NotifAction> = emptyList(),
    val createdAt: Instant,
)

/**
 * Grouped notifications keyed by [sourceKey]. Re-pushing with the
 * same `sourceKey` appends an event to the existing group rather
 * than creating a parallel card.
 *
 * Why grouped: a single pack launch generates Prepare -> Downloading
 * -> Running -> Exited within a session. Four separate cards would
 * dominate the stack; one group with a counter + chevron-to-history
 * keeps the visual quiet and the timeline accessible.
 *
 * `events` is newest-first and bounded ([NotificationCenter] caps at
 * a small N; older events fall off the tail) so a runaway driver
 * cannot blow up memory.
 */
data class NotificationGroup(
    val sourceKey: String,
    val sender: String,
    val avatar: AvatarSource,
    val events: List<NotificationEvent>,
) {
    init {
        require(events.isNotEmpty()) {
            "NotificationGroup($sourceKey) must contain at least one event"
        }
    }

    /** Most-recent event in the group; what the collapsed card renders. */
    val latest: NotificationEvent get() = events.first()

    /**
     * Group severity = the max severity across its events. A Critical
     * event in any position of the history keeps the group critical
     * even after a subsequent Info / Success arrives, so the user
     * cannot accidentally miss a past hard failure that scrolled
     * past.
     */
    val severity: Severity get() = events.maxOf { it.severity }

    /** Count >= 2 unlocks the chevron + history-expand affordance. */
    val count: Int get() = events.size
}
