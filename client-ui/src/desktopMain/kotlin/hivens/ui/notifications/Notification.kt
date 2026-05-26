package hivens.ui.notifications

import java.time.Instant
import java.util.UUID

sealed class AvatarSource {
    data class Url(val url: String) : AvatarSource()
    data class Glyph(val name: String) : AvatarSource()
    data object Generic : AvatarSource()
}

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
    val title: String,
    val body: String? = null,
    val progress: Float? = null,
    val actions: List<NotifAction> = emptyList(),
    val createdAt: Instant,
)

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

    val latest: NotificationEvent get() = events.first()

    // max across events, not events.first.severity -- a past Critical
    // must keep the group visually marked even after a later Info.
    val severity: Severity get() = events.maxOf { it.severity }

    val count: Int get() = events.size
}
