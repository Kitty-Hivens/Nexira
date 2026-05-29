package hivens.launcher

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SurfaceId
import hivens.widget.model.instanceIds
import hivens.widget.model.removeInstanceIds
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * JSON-on-disk store for the widget [LayoutGraph]. Single-file envelope
 * with a version tag, atomic write via `<file>.tmp` + ATOMIC_MOVE,
 * malformed-file falls back to the bundled default rather than crashing
 * the launcher.
 *
 * Wire format:
 * ```
 * { "schema_version": N, "graph": { "surfaces": { ... } } }
 * ```
 *
 * Disk writes are debounced ([DEBOUNCE_MS] after the last [update]) so
 * a drag-thrash gesture across nested containers produces ~1 write per
 * 200ms rather than dozens. [flush] forces the pending write
 * synchronously and is called from a JVM shutdown hook so a SIGTERM
 * mid-edit does not lose work.
 *
 * Each [update] also runs a tree-wide [instanceId] uniqueness check;
 * a transform that produces duplicate ids (e.g. a buggy mixin in
 * Phase C, or a paste-with-children that does not rewrite ids) is
 * rejected with a warn log and identity return -- a single duplicate
 * instanceId would otherwise corrupt every findByInstanceId-style
 * traversal in the editor.
 */
class LayoutGraphRepository(
    private val file: Path,
    private val json: Json,
    private val scope: CoroutineScope,
    private val defaultGraph: () -> LayoutGraph,
) {

    private val log   = LoggerFactory.getLogger(LayoutGraphRepository::class.java)
    private val mutex = Mutex()
    private val state: MutableStateFlow<LayoutGraph> = MutableStateFlow(load())

    // Pending debounced persist. Replaced on each update; cancelled by
    // flush() which then persists synchronously under the same mutex.
    private var pendingWrite: Job? = null

    fun observe(): StateFlow<LayoutGraph> = state.asStateFlow()

    fun value(): LayoutGraph = state.value

    /**
     * Read-modify-write under [mutex]. The transform runs synchronously
     * against the current snapshot; if it returns the same graph
     * reference (no-op transform) or a graph with duplicate instance
     * ids (invalid mutation), no state change and no disk write. On
     * success the StateFlow re-emits immediately and a write is
     * scheduled [DEBOUNCE_MS] in the future, replacing any previous
     * pending write.
     */
    suspend fun update(transform: (LayoutGraph) -> LayoutGraph) {
        mutex.withLock {
            val next = transform(state.value)
            if (next == state.value) return@withLock

            // Tree-wide instanceId uniqueness check. Walks the whole
            // tree including nested children. Sequence-based so we
            // short-circuit on the first dup.
            val seen = HashSet<String>()
            for (widget in next.walkInstances()) {
                if (!seen.add(widget.instanceId)) {
                    log.warn(
                        "Layout update rejected: duplicate instanceId '{}' in tree. " +
                        "Keeping previous graph to protect findByInstanceId-style traversals.",
                        widget.instanceId,
                    )
                    return@withLock
                }
            }

            state.value = next

            // Cancel-and-reschedule debounce. Coalesces drag-thrash
            // gestures into ~1 write per DEBOUNCE_MS window.
            pendingWrite?.cancel()
            pendingWrite = scope.launch {
                try {
                    delay(DEBOUNCE_MS)
                    mutex.withLock { writeNow() }
                } catch (_: CancellationException) {
                    // Superseded by a later update() or flushed
                    // synchronously; either way no persist needed here.
                }
            }
        }
    }

    /**
     * Resets one surface to its bundled default. Useful as an escape
     * hatch when the user has dropped a non-removable widget into a
     * surface it doesn't belong on, or wants to roll back accidental
     * structural changes. Goes through the standard [update] path, so
     * the tree-wide uniqueness check still runs, the StateFlow
     * re-emits, and the disk write is debounced.
     *
     * Surface absent from the bundled default = removed entirely from
     * the live graph (matches "this surface no longer exists in the
     * default layout" semantics, e.g. a plugin-introduced surface that
     * was uninstalled).
     */
    suspend fun resetSurface(surface: SurfaceId) {
        val def = defaultGraph()
        update { graph ->
            val defaultLayout = def.surfaces[surface]
                ?: return@update graph.copy(surfaces = graph.surfaces - surface)
            // Ids the restored default reintroduces. If any leaked onto
            // OTHER surfaces via a cross-surface move, strip them there
            // first -- otherwise the restored default id collides with the
            // leaked copy and the tree-wide uniqueness check in update()
            // rejects the whole reset, trapping the user. Reset is the
            // escape hatch; it must always succeed.
            val restoredIds = defaultLayout.instanceIds()
            val cleaned = graph.surfaces.mapValues { (sid, layout) ->
                if (sid == surface) layout else layout.removeInstanceIds(restoredIds)
            }
            graph.copy(surfaces = cleaned + (surface to defaultLayout))
        }
    }

    /**
     * Forces any pending debounced write to land on disk before
     * returning. Cancels the in-flight debounce coroutine (if any) and
     * persists the current state synchronously under [mutex]. Safe to
     * call from a JVM shutdown hook (does no suspending I/O dispatch
     * switch; the calling thread does the file write directly).
     *
     * No-op when nothing is pending and the on-disk file is already up
     * to date.
     */
    suspend fun flush() {
        mutex.withLock {
            val pending = pendingWrite
            pendingWrite = null
            if (pending == null) return@withLock
            pending.cancel()
            writeNow()
        }
    }

    private fun load(): LayoutGraph {
        if (!Files.exists(file)) {
            // First run: write the bundled default so subsequent loads
            // operate on the user's editable copy, not the jar resource.
            // Failure to write is non-fatal -- the launcher still runs
            // against the in-memory default.
            val def = defaultGraph()
            try {
                Files.createDirectories(file.parent)
                val envelope = Envelope(schemaVersion = SCHEMA_VERSION, graph = def)
                Files.writeString(file, json.encodeToString(envelope))
            } catch (e: Exception) {
                log.warn("Failed to seed default layout graph at {} -- continuing in memory", file, e)
            }
            return def
        }
        return try {
            val envelope = json.decodeFromString<Envelope>(Files.readString(file))
            val migrated = Migrations.apply(envelope.schemaVersion, envelope.graph)
            mergeMissingSurfaces(migrated, defaultGraph())
        } catch (e: Exception) {
            log.error("Failed to load layout graph at {} -- falling back to bundled default", file, e)
            defaultGraph()
        }
    }

    // Merge surfaces from the bundled default into the user's persisted
    // graph when the user file pre-dates the surface. Without this, a
    // surface added to default-layout.json in a later release stays
    // invisible because the user file is the source of truth on load.
    //
    // Only ADDS missing surfaces. Surfaces the user has edited keep
    // their persisted form; surfaces the user has reset-via-editor
    // stay reset (the reset path writes the default explicitly). A
    // surface dropped from default-layout in a later release likewise
    // stays in the user file -- there is no automatic deletion path,
    // because a removed-upstream surface may carry user data we cannot
    // recreate.
    private fun mergeMissingSurfaces(user: LayoutGraph, def: LayoutGraph): LayoutGraph {
        val missing = def.surfaces.filterKeys { it !in user.surfaces }
        if (missing.isEmpty()) return user
        log.info("Layout graph: seeding {} new surface(s) from bundled default: {}", missing.size, missing.keys.map { it.value })
        return user.copy(surfaces = user.surfaces + missing)
    }

    // Synchronous file ops. Caller MUST hold [mutex] when invoking.
    // The debounce coroutine acquires the mutex inside its launched
    // block; flush() invokes from inside its own mutex.withLock.
    private fun writeNow() {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            val envelope = Envelope(schemaVersion = SCHEMA_VERSION, graph = state.value)
            Files.writeString(tmp, json.encodeToString(envelope))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                log.warn(
                    "Filesystem at {} does not support ATOMIC_MOVE; " +
                    "falling back to non-atomic rename for layout-graph.json. " +
                    "Move the data directory to a filesystem that supports " +
                    "atomic rename (ext4, NTFS, APFS) for full durability.",
                    file.parent,
                )
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.error("Failed to persist layout graph at {}", file, e)
        }
    }

    @Serializable
    private data class Envelope(
        @SerialName("schema_version") val schemaVersion: Int,
        val graph: LayoutGraph,
    )

    private companion object {
        const val SCHEMA_VERSION = 2

        // 200ms catches a drag-thrash without delaying single drops
        // noticeably. Verification target in the Phase A plan is "<=5
        // disk writes over a 1s drag-thrash".
        const val DEBOUNCE_MS = 200L
    }
}

/**
 * Schema migration ladder. Each step transforms a graph from version
 * N-1 to version N. Phase A bumps to v2 (forward-compat marker for
 * nested children -- the field defaults to empty, so v1 data parses
 * transparently as v2).
 */
internal object Migrations {
    fun apply(fromVersion: Int, graph: LayoutGraph): LayoutGraph {
        // Reject corrupted versions before entering the loop. A bogus
        // `"schema_version": -2147483648` would otherwise spin through
        // ~2 billion no-op iterations and hang the launcher on startup;
        // throwing here routes through load()'s catch and falls back
        // to the bundled default.
        require(fromVersion >= 1) { "schema_version must be >= 1, got $fromVersion" }
        if (fromVersion >= CURRENT) return graph
        var current = graph
        for (step in fromVersion until CURRENT) {
            current = step(step + 1).apply(current)
        }
        return current
    }

    private const val CURRENT = 2

    private fun step(toVersion: Int): Step = when (toVersion) {
        // v1 -> v2: WidgetInstance gained a children field. Default
        // value handles backward compat on deserialization, so the
        // step itself is identity.
        2 -> Step.IDENTITY
        else -> Step.IDENTITY
    }

    private fun interface Step {
        fun apply(graph: LayoutGraph): LayoutGraph
        companion object {
            val IDENTITY = Step { it }
        }
    }
}
