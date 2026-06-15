package hivens.ui.notifications

import java.time.Instant
import java.util.UUID

// `id` is stable across i18n rebrands; tests + puppet observers key on it.
data class NotifAction(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
)

// progress: null -> no bar, 0..1 -> fraction, NaN -> indeterminate.
// NaN sentinel matches AprilFoolsProgress.wrap so download ticks pipe through.
data class NotificationEvent(
    val id: UUID,
    val severity: Severity,
    val kind: Kind,
    val title: String,
    val body: String? = null,
    val progress: Float? = null,
    val actions: List<NotifAction> = emptyList(),
    val createdAt: Instant,
)

data class NotificationGroup(
    val sourceKey: String,
    val sender: String,
    // Direct URL string; null = renderer falls back to a neutral
    // placeholder. The previous sealed Url/Glyph/Generic carried
    // future-extension shape without a real consumer; recover the
    // indirection only when a second source type is needed.
    val iconUrl: String?,
    // Vector glyph fallback when there is no [iconUrl] (launcher-generated
    // events like an available update). Null falls through to the neutral
    // placeholder, same as a source with neither.
    val glyph: NotifGlyph? = null,
    val events: List<NotificationEvent>,
) {
    init {
        require(events.isNotEmpty()) {
            "NotificationGroup($sourceKey) must contain at least one event"
        }
    }

    val latest: NotificationEvent get() = events.first()

    // max across events, not events.first.severity -- a past Critical
    // must keep the group visually marked even after a later Info.
    val severity: Severity get() = events.maxOf { it.severity }

    // Kind tracks the current lifecycle, so it follows the latest event.
    // The auto-dismiss decision still consults `severity` (max across) so
    // a group that ever held Critical keeps the longer window once it
    // transitions to OneShot.
    val kind: Kind get() = latest.kind

    val count: Int get() = events.size
}
