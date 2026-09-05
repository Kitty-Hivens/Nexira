package hivens.ui.layout

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.resetSurface
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
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyStore
import hivens.core.io.AtomicFiles
import hivens.ui.bootstrap.RecoveryIo
import kotlin.time.Duration.Companion.milliseconds

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
 * traversal in the editor. [load] runs the same sweep on the
 * post-migration graph and falls back to the bundled default on a
 * collision, so a migration that mints a clashing id can't persist one.
 */
class LayoutGraphRepository(
    private val file: Path,
    private val json: Json,
    private val scope: CoroutineScope,
    private val defaultGraph: () -> LayoutGraph,
) {

    private val log   = LoggerFactory.getLogger(LayoutGraphRepository::class.java)
    private val mutex = Mutex()

    // Set by load() when the file's schema_version is newer than this build
    // understands. A newer build wrote it; we read best-effort (and keep the
    // UI live) but never write back, so an older binary cannot downgrade and
    // discard layout data it can't represent. Symmetric to Migrations.apply's
    // lower-bound rejection.
    @Volatile private var readOnly = false

    @Volatile private var _migratedFromSchema: Int? = null

    /**
     * The schema version the just-loaded file was migrated UP from, or null
     * when the file was already current, a newer build's, first-run, or
     * unreadable. Read once at startup to gate destructive reconciliation
     * (the unknown-kind prune) on an actual schema bump.
     */
    val migratedFromSchema: Int? get() = _migratedFromSchema

    private val state: MutableStateFlow<LayoutGraph> = MutableStateFlow(load())

    // Pending debounced persist. Replaced on each update; cancelled by
    // flush() which then persists synchronously under the same mutex.
    private var pendingWrite: Job? = null

    // Whether the state flow holds an edit the file does not. pendingWrite alone
    // could not answer that: it is replaced on every update and nulled only by
    // flush, so a long-completed job left flush believing a write was still owed.
    @Volatile private var dirty = false

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
    suspend fun update(validate: Boolean = true, transform: (LayoutGraph) -> LayoutGraph) {
        mutex.withLock {
            val next = transform(state.value)
            if (next == state.value) return@withLock

            // Tree-wide instanceId uniqueness check. Walks the whole tree
            // including nested children; short-circuits on the first dup.
            // Skipped (validate = false) for geometry-only transforms (offset /
            // size / z / weight) that fire per drag frame and provably cannot
            // introduce a duplicate id -- otherwise this walk runs ~60x/sec.
            if (validate) {
                LayoutReconcile.firstDuplicateInstanceId(next)?.let { dup ->
                    log.warn(
                        "Layout update rejected: duplicate instanceId '{}' in tree. " +
                            "Keeping previous graph to protect findByInstanceId-style traversals.",
                        dup,
                    )
                    return@withLock
                }
            }

            state.value = next
            dirty = true

            // Cancel-and-reschedule debounce. Coalesces drag-thrash
            // gestures into ~1 write per DEBOUNCE_MS window.
            pendingWrite?.cancel()
            pendingWrite = scope.launch {
                try {
                    delay(DEBOUNCE_MS.milliseconds)
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
        update { it.resetSurface(surface, def.surfaces[surface]) }
    }

    /**
     * Full reset: restore the entire graph to the bundled default. The escape
     * hatch when per-surface resets are not enough -- e.g. undoing edits spread
     * across several surfaces in one action.
     */
    suspend fun resetAll() {
        update { defaultGraph() }
    }

    /**
     * Forces any pending debounced write to land on disk before
     * returning. Cancels the in-flight debounce coroutine (if any) and
     * persists the current state synchronously under [mutex]. Safe to
     * call from a JVM shutdown hook (does no suspending I/O dispatch
     * switch; the calling thread does the file write directly).
     *
     * No-op when the file already matches the graph in memory. That matters
     * beyond saving a write: this runs from a shutdown hook, and on the path
     * where the recovery surface has just deleted the file, writing it back
     * would undo the reset the user asked for.
     */
    suspend fun flush() {
        mutex.withLock {
            if (!dirty) return@withLock
            pendingWrite?.cancel()
            pendingWrite = null
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
                AtomicFiles.writeString(file, json.encodeToString(Envelope(schemaVersion = SCHEMA_VERSION, graph = def)))
            } catch (e: Exception) {
                log.warn("Failed to seed default layout graph at {} -- continuing in memory", file, e)
            }
            return def
        }
        return try {
            val envelope = json.decodeFromString<Envelope>(Files.readString(file))
            if (envelope.schemaVersion > SCHEMA_VERSION) {
                readOnly = true
                NewerBuildData.record(ReadOnlyStore.Layout)
                log.warn(
                    "Layout graph at {} is schema_version {} > supported {} -- written by a newer build. " +
                        "Loading read-only; this session will not write it back to avoid clobbering newer data.",
                    file, envelope.schemaVersion, SCHEMA_VERSION,
                )
            }
            if (envelope.schemaVersion in 1 until LayoutReconcile.SURFACE_SCHEMA) {
                // Deliberately not migrated: see LayoutReconcile.SURFACE_SCHEMA. The
                // file is left on disk untouched and simply not read, so a build that
                // still understands it can be gone back to.
                log.warn(
                    "Layout graph at {} is schema_version {} and describes widget surfaces in a form " +
                        "with no faithful reading here; starting from the bundled default. " +
                        "The file is left as it is.",
                    file, envelope.schemaVersion,
                )
                return defaultGraph()
            }
            if (envelope.schemaVersion in 1 until SCHEMA_VERSION) {
                _migratedFromSchema = envelope.schemaVersion
            }
            val def = defaultGraph()
            // Migrate + seed missing default surfaces/slots + sweep instanceId
            // uniqueness via the shared reconciler. A migration or merge that
            // mints a colliding id would silently break every findByInstanceId
            // traversal; update() guards live edits, this is the load-time
            // backstop -- serve the bundled default over a corrupted tree.
            when (val result = LayoutReconcile.reconcile(envelope.schemaVersion, envelope.graph, def)) {
                is LayoutReconcile.Result.Ok -> result.graph
                is LayoutReconcile.Result.DuplicateId -> {
                    log.error(
                        "Layout graph at {} has a duplicate instanceId '{}' after {} (schema_version {} -> {}). " +
                            "Falling back to the bundled default to protect instanceId-keyed traversals.",
                        file, result.id, result.stage, envelope.schemaVersion, LayoutReconcile.CURRENT_SCHEMA,
                    )
                    defaultGraph()
                }
            }
        } catch (e: Exception) {
            log.error("Failed to load layout graph at {} -- falling back to bundled default", file, e)
            defaultGraph()
        }
    }

    // Synchronous file ops. Caller MUST hold [mutex] when invoking.
    // The debounce coroutine acquires the mutex inside its launched
    // block; flush() invokes from inside its own mutex.withLock.
    private fun writeNow() {
        if (RecoveryIo.stateWasReset) {
            log.debug("Layout graph was reset from the recovery surface -- not writing the in-memory copy back")
            return
        }
        if (readOnly) {
            log.debug("Layout graph at {} is from a newer build -- skipping write-back", file)
            return
        }
        try {
            val envelope = Envelope(schemaVersion = SCHEMA_VERSION, graph = state.value)
            AtomicFiles.writeString(file, json.encodeToString(envelope))
            dirty = false
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
        const val SCHEMA_VERSION = LayoutReconcile.CURRENT_SCHEMA

        // 200ms catches a drag-thrash without delaying single drops
        // noticeably. Verification target in the Phase A plan is "<=5
        // disk writes over a 1s drag-thrash".
        const val DEBOUNCE_MS = 200L
    }
}

/**
 * Schema migration ladder. Each step transforms a graph from version N-1 to version N.
 *
 * Empty at present, and that is not an oversight. Everything below
 * [LayoutReconcile.SURFACE_SCHEMA] is discarded at load rather than migrated, so the
 * steps that once carried a graph from v1 to v7 are unreachable and have been removed
 * with their tests. The mechanism stays because the next schema change will be an
 * ordinary one: the format this build writes is built to grow rather than break.
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

    private const val CURRENT = LayoutReconcile.CURRENT_SCHEMA

    private fun step(toVersion: Int): Step = Step.IDENTITY

    private fun interface Step {
        fun apply(graph: LayoutGraph): LayoutGraph
        companion object {
            val IDENTITY = Step { it }
        }
    }
}
