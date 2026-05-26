package hivens.ui.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

// Mutations go through StateFlow.update (CAS retry) so concurrent
// drivers on different coroutines do not lose events.
class NotificationCenter(
    private val historyPerGroup: Int = DEFAULT_HISTORY_PER_GROUP,
    private val clock: () -> Instant = Instant::now,
) {
    private val _groups = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val groups: StateFlow<List<NotificationGroup>> = _groups

    fun push(
        sourceKey: String,
        sender: String,
        avatar: AvatarSource,
        severity: Severity,
        title: String,
        body: String? = null,
        progress: Float? = null,
        actions: List<NotifAction> = emptyList(),
    ): String {
        val event = NotificationEvent(
            id        = UUID.randomUUID(),
            severity  = severity,
            title     = title,
            body      = body,
            progress  = progress,
            actions   = actions,
            createdAt = clock(),
        )
        _groups.update { current ->
            val existingIndex = current.indexOfFirst { it.sourceKey == sourceKey }
            if (existingIndex < 0) {
                listOf(
                    NotificationGroup(
                        sourceKey = sourceKey,
                        sender    = sender,
                        avatar    = avatar,
                        events    = listOf(event),
                    )
                ) + current
            } else {
                val existing = current[existingIndex]
                val merged = existing.copy(
                    sender = sender,
                    avatar = avatar,
                    events = (listOf(event) + existing.events).take(historyPerGroup),
                )
                val others = current.toMutableList().also { it.removeAt(existingIndex) }
                listOf(merged) + others
            }
        }
        return sourceKey
    }

    fun dismiss(sourceKey: String) {
        _groups.update { current -> current.filterNot { it.sourceKey == sourceKey } }
    }

    fun clear() {
        _groups.value = emptyList()
    }

    companion object {
        const val DEFAULT_HISTORY_PER_GROUP = 20
    }
}
