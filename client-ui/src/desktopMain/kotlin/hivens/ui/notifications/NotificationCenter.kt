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
    private val maxGroups: Int = DEFAULT_MAX_GROUPS,
    private val clock: () -> Instant = Instant::now,
    // Durable-log hook. Every push forwards a serializable projection here; the
    // default no-op keeps the center disk-free and testable on its own.
    private val archive: (PersistedNotification) -> Unit = {},
    // "Do not disturb" seed + persist. Default off + no-op keeps the center
    // disk-free and testable on its own, mirroring `archive`; the app wires the
    // seed from settings and persists the flip back.
    initialDoNotDisturb: Boolean = false,
    private val persistDoNotDisturb: (Boolean) -> Unit = {},
) {
    private val _groups = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val groups: StateFlow<List<NotificationGroup>> = _groups

    // When true, the live popup stack is muted -- pushes still record to the
    // archive (history keeps filling) and still auto-dismiss; only the toast
    // rendering is suppressed (gated in NotificationStack).
    private val _doNotDisturb = MutableStateFlow(initialDoNotDisturb)
    val doNotDisturb: StateFlow<Boolean> = _doNotDisturb

    fun setDoNotDisturb(value: Boolean) {
        if (_doNotDisturb.value == value) return
        _doNotDisturb.value = value
        persistDoNotDisturb(value)
    }

    fun push(
        sourceKey: String,
        sender: String,
        iconUrl: String?,
        severity: Severity,
        kind: Kind,
        title: String,
        body: String? = null,
        progress: Float? = null,
        actions: List<NotifAction> = emptyList(),
        glyph: NotifGlyph? = null,
    ): String {
        val event = NotificationEvent(
            id        = UUID.randomUUID(),
            severity  = severity,
            kind      = kind,
            title     = title,
            body      = body,
            progress  = progress,
            actions   = actions,
            createdAt = clock(),
        )
        archive(
            PersistedNotification(
                sourceKey      = sourceKey,
                sender         = sender,
                iconUrl        = iconUrl,
                glyph          = glyph,
                severity       = severity,
                kind           = kind,
                title          = title,
                body           = body,
                createdAtEpoch = event.createdAt.epochSecond,
            )
        )
        _groups.update { current ->
            val existingIndex = current.indexOfFirst { it.sourceKey == sourceKey }
            val newList = if (existingIndex < 0) {
                listOf(
                    NotificationGroup(
                        sourceKey = sourceKey,
                        sender    = sender,
                        iconUrl   = iconUrl,
                        glyph     = glyph,
                        events    = listOf(event),
                    )
                ) + current
            } else {
                val existing = current[existingIndex]
                // Coalesce a run of live progress: while the group head is a
                // Progress event and the incoming one is too, REPLACE it rather
                // than stacking. A ~10/sec download tick would otherwise bury the
                // group in identical "Syncing X" rows. A terminal (non-Progress)
                // event prepends normally, so history keeps the outcome plus the
                // last progress snapshot.
                val coalesce = kind == Kind.Progress && existing.latest.kind == Kind.Progress
                val mergedEvents = if (coalesce) {
                    listOf(event) + existing.events.drop(1)
                } else {
                    (listOf(event) + existing.events).take(historyPerGroup)
                }
                val merged = existing.copy(
                    sender  = sender,
                    iconUrl = iconUrl,
                    glyph   = glyph,
                    events  = mergedEvents,
                )
                val others = current.toMutableList().also { it.removeAt(existingIndex) }
                listOf(merged) + others
            }
            // Drop oldest groups beyond cap; symmetric to historyPerGroup.
            // Without it a future driver pushing N distinct sourceKeys grows
            // _groups unboundedly while the visible stack stays at 4.
            newList.take(maxGroups)
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
        const val DEFAULT_MAX_GROUPS = 32
    }
}
