package hivens.ui.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/**
 * Process-singleton notification surface. Drivers push events keyed
 * by `sourceKey`; the center merges them into [NotificationGroup]s,
 * caps per-group event history, and exposes the current stack as a
 * [StateFlow] for [hivens.ui.notifications.render.NotificationStack]
 * to render.
 *
 * Threading: every mutation goes through [MutableStateFlow.update]
 * (compare-and-set retry loop), so concurrent drivers on different
 * coroutines do not lose events. Reading is lock-free.
 *
 * Persistence: zero. The stack lives in memory only; restarts wipe
 * the history. Acceptable for transient launch / sync / update
 * surfaces; a future per-pack notification log would be its own
 * subsystem.
 */
class NotificationCenter(
    private val historyPerGroup: Int = DEFAULT_HISTORY_PER_GROUP,
    private val clock: () -> Instant = Instant::now,
) {
    private val _groups = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val groups: StateFlow<List<NotificationGroup>> = _groups

    /**
     * Push a new event onto the group identified by [sourceKey],
     * creating the group when this is the first event from that key.
     *
     * Returns the resulting group's `sourceKey` for symmetry; callers
     * track lifecycle via the key, not by event id, because the
     * underlying event ids get replaced as history rolls over.
     */
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
                // Newer groups render at the top of the visible stack;
                // prepending keeps the most-recently-active source
                // closest to the viewer.
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
                // Float the touched group to the front -- the user's
                // attention should follow whichever source last
                // generated activity.
                val others = current.toMutableList().also { it.removeAt(existingIndex) }
                listOf(merged) + others
            }
        }
        return sourceKey
    }

    /**
     * Drop a group from the visible stack. Idempotent; calling on an
     * unknown sourceKey is a no-op.
     */
    fun dismiss(sourceKey: String) {
        _groups.update { current -> current.filterNot { it.sourceKey == sourceKey } }
    }

    /** Clear everything. Used on logout / data-dir-move to avoid stale cards. */
    fun clear() {
        _groups.value = emptyList()
    }

    companion object {
        const val DEFAULT_HISTORY_PER_GROUP = 20
    }
}
