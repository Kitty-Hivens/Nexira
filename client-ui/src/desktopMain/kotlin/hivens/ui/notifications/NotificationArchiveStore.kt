package hivens.ui.notifications

import hivens.core.io.AtomicFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Durable, capped message log read by the notification history widget. Unlike
 * the live [NotificationCenter] stack (in-memory, auto-dismissing), this
 * survives auto-dismiss AND restart -- it is the "what happened" record.
 *
 * Write policy: the in-memory [log] updates on every [record], but the disk is
 * written only on settled (non-[Kind.Progress]) events. A ~10/sec download tick
 * updates memory but never touches the disk; the worst a crash loses is an
 * in-flight percentage, which nobody needs persisted.
 *
 * Persistence shape mirrors `JsonPackRepository`: a versioned wrapper written
 * atomically via [AtomicFiles]. Writes run on the injected [scope] (the app's
 * IO scope in production; a test dispatcher in tests) and are serialized by a
 * [Mutex] that re-reads the latest snapshot under the lock, so concurrent
 * records never write stale-out-of-order state.
 */
class NotificationArchiveStore(
    private val file: Path,
    private val json: Json,
    private val scope: CoroutineScope,
    private val cap: Int = DEFAULT_CAP,
) {
    private val logger = LoggerFactory.getLogger(NotificationArchiveStore::class.java)
    private val writeMutex = Mutex()

    private val _log = MutableStateFlow(load())
    val log: StateFlow<List<PersistedNotification>> = _log.asStateFlow()

    fun record(entry: PersistedNotification) {
        _log.update { current -> merge(current, entry) }
        if (entry.kind != Kind.Progress) persistAsync()
    }

    fun clear() {
        _log.value = emptyList()
        persistAsync()
    }

    // Drop every entry matching [predicate] -- the history widget uses it to
    // dismiss a single (grouped) message by swipe.
    fun remove(predicate: (PersistedNotification) -> Boolean) {
        _log.update { current -> current.filterNot(predicate) }
        persistAsync()
    }

    // Coalesce a live progress run per source, mirroring the live stack: while
    // the head is a progress entry for the same source and the incoming one is
    // too, replace it instead of stacking. Newest first; capped.
    private fun merge(
        current: List<PersistedNotification>,
        entry: PersistedNotification,
    ): List<PersistedNotification> {
        val head = current.firstOrNull()
        val coalesce = entry.kind == Kind.Progress &&
            head != null && head.kind == Kind.Progress && head.sourceKey == entry.sourceKey
        val rest = if (coalesce) current.drop(1) else current
        return (listOf(entry) + rest).take(cap)
    }

    private fun persistAsync() {
        scope.launch {
            writeMutex.withLock {
                // Read under the lock so the latest state wins; redundant writes
                // are idempotent.
                val snapshot = _log.value
                runCatching {
                    AtomicFiles.writeString(file, json.encodeToString(ArchiveFile(items = snapshot)))
                }.onFailure { logger.warn("Failed to persist notification archive to {}", file, it) }
            }
        }
    }

    private fun load(): List<PersistedNotification> = runCatching {
        if (!Files.exists(file)) emptyList()
        else json.decodeFromString<ArchiveFile>(Files.readString(file)).items.take(cap)
    }.getOrElse {
        logger.warn("Failed to load notification archive from {}; starting empty", file, it)
        emptyList()
    }

    @Serializable
    private data class ArchiveFile(
        val version: Int = 1,
        val items: List<PersistedNotification>,
    )

    companion object {
        const val DEFAULT_CAP = 200
    }
}
