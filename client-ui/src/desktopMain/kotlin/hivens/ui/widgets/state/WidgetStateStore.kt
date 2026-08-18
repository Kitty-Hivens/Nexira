package hivens.ui.widgets.state

import hivens.core.io.AtomicFiles
import hivens.ui.bootstrap.RecoveryIo
import hivens.widget.api.WidgetStateHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Disk store for per-instance widget state ([WidgetStateHost]). Separate from the
 * layout graph on purpose: runtime state has a different lifecycle (a layout undo
 * must not revert a user's notes) and write cadence (per keystroke). Keyed by the
 * widget instanceId -- stable across restart, preserved by moveWidget, never reused
 * -- so state follows a moved widget and dies with a removed one (the GC collector
 * prunes orphans; see [WidgetStateGc]).
 *
 * Wire format mirrors the layout envelope:
 * ```
 * { "version": 1, "entries": { "<instanceId>": { ...state... } } }
 * ```
 *
 * In-memory writes are immediate; disk writes are debounced ([DEBOUNCE_MS]) so a
 * burst of keystrokes coalesces to ~1 write. [flush] forces the pending write for
 * the shutdown hook. A corrupt file loads as empty (whole store); a corrupt single
 * entry is the widget's concern (it falls back to its default at decode).
 */
@OptIn(FlowPreview::class)
class WidgetStateStore(
    private val file: Path,
    private val json: Json,
    scope: CoroutineScope,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
) : WidgetStateHost {

    private val log = LoggerFactory.getLogger(WidgetStateStore::class.java)
    private val writeMutex = Mutex()

    // Whether the map holds an edit the file does not. Without it flush wrote
    // unconditionally, so the shutdown hook rewrote the file even on the path
    // where the recovery surface had just deleted it.
    @Volatile private var dirty = false
    private val entries = MutableStateFlow(load())

    // Coalesces a keystroke burst into one disk write. tryEmit keeps store()
    // non-suspending and lock-free; the collector debounces and persists. replay = 1
    // so a request emitted before the collector has subscribed (e.g. a store() in the
    // same tick as construction) is not lost.
    private val writeRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            writeRequests.debounce(DEBOUNCE_MS.milliseconds).collect { writeMutex.withLock { writeNow() } }
        }
    }

    override fun load(instanceId: String): JsonObject? = entries.value[instanceId]

    override fun store(instanceId: String, value: JsonObject) {
        // Guard against a runaway widget bloating the file unbounded. Orphan GC
        // already bounds entry COUNT to live instances; this bounds per-entry SIZE.
        val encoded = json.encodeToString(JsonObject.serializer(), value)
        if (encoded.length > maxEntryBytes) {
            log.warn("Widget state for '{}' is {} chars (> {} cap) -- not persisted", instanceId, encoded.length, maxEntryBytes)
            return
        }
        entries.update { it + (instanceId to value) }
        dirty = true
        writeRequests.tryEmit(Unit)
    }

    /** Drops one instance's state. Called by the GC collector, not by widgets. */
    fun remove(instanceId: String) {
        if (instanceId !in entries.value) return
        entries.update { it - instanceId }
        dirty = true
        writeRequests.tryEmit(Unit)
    }

    /** Prunes state for instanceIds no longer present in the layout graph. */
    fun retain(liveIds: Set<String>) {
        if (entries.value.keys.all { it in liveIds }) return
        entries.update { current -> current.filterKeys { it in liveIds } }
        dirty = true
        writeRequests.tryEmit(Unit)
    }

    /**
     * Forces the pending debounced write to land synchronously. For the JVM
     * shutdown hook so a note typed inside the debounce window is not lost on quit.
     *
     * No-op when the file already matches the map. That matters beyond saving a
     * write: on the path where the recovery surface has just deleted the file,
     * writing it back would undo the reset the user asked for.
     */
    suspend fun flush() {
        writeMutex.withLock { if (dirty) writeNow() }
    }

    // Caller must hold writeMutex.
    private fun writeNow() {
        if (RecoveryIo.stateWasReset) {
            log.debug("Widget state was reset from the recovery surface -- not writing the in-memory copy back")
            return
        }
        runCatching {
            AtomicFiles.writeString(file, json.encodeToString(Envelope.serializer(), Envelope(entries = entries.value)))
            dirty = false
        }.onFailure { log.warn("Failed to persist widget state to {}", file, it) }
    }

    // No eager seed: the file is created on the first store(), so a user who never
    // places a stateful widget never gets an empty widget-state.json.
    private fun load(): Map<String, JsonObject> = runCatching {
        if (!Files.exists(file)) emptyMap()
        else json.decodeFromString(Envelope.serializer(), Files.readString(file)).entries
    }.getOrElse {
        log.warn("Failed to load widget state from {}; starting empty", file, it)
        emptyMap()
    }

    @Serializable
    private data class Envelope(
        val version: Int = 1,
        val entries: Map<String, JsonObject> = emptyMap(),
    )

    private companion object {
        const val DEBOUNCE_MS = 200L
        const val DEFAULT_MAX_ENTRY_BYTES = 64 * 1024
    }
}
