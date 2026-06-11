package hivens.launcher

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.flatMapInstances
import hivens.widget.model.resetSurface
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import hivens.core.io.AtomicFiles
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

    // Set by load() when the file's schema_version is newer than this build
    // understands. A newer build wrote it; we read best-effort (and keep the
    // UI live) but never write back, so an older binary cannot downgrade and
    // discard layout data it can't represent. Symmetric to Migrations.apply's
    // lower-bound rejection.
    @Volatile private var readOnly = false

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
            }

            state.value = next

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
                log.warn(
                    "Layout graph at {} is schema_version {} > supported {} -- written by a newer build. " +
                        "Loading read-only; this session will not write it back to avoid clobbering newer data.",
                    file, envelope.schemaVersion, SCHEMA_VERSION,
                )
            }
            val def      = defaultGraph()
            val migrated = Migrations.apply(envelope.schemaVersion, envelope.graph)
            mergeMissingSlots(mergeMissingSurfaces(migrated, def), def)
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

    // Merge slots from the bundled default into a surface the user
    // already has, when the default declares a slot the user file lacks.
    // mergeMissingSurfaces only adds whole NEW surfaces; a slot ADDED to
    // an existing surface in a later release (e.g. the profile sign-in
    // slot) would otherwise stay invisible, because the user's surface
    // is the source of truth on load and SlotRenderer finds nothing at
    // the new slot id -- a blank pane with no in-product way back.
    //
    // Slots are structural: the editor arranges widgets WITHIN slots but
    // has no create-slot or delete-slot operation. So a slot present in
    // the default but absent from the user graph is always an upstream
    // addition, never a user deletion -- which makes this merge purely
    // additive and safe (it cannot resurrect something the user removed).
    // Slot REMOVALS (a slot dropped from the default) are left in place;
    // a stale slot is inert in production -- surface composables render
    // slots by explicit id -- and a true removal that must reclaim the
    // data needs an explicit migration step instead.
    private fun mergeMissingSlots(user: LayoutGraph, def: LayoutGraph): LayoutGraph {
        var changed = false
        val merged = user.surfaces.mapValues { (surfaceId, layout) ->
            val defLayout = def.surfaces[surfaceId] ?: return@mapValues layout
            val missing   = defLayout.slots.filterKeys { it !in layout.slots }
            if (missing.isEmpty()) return@mapValues layout
            changed = true
            log.info(
                "Layout graph: seeding {} new slot(s) into surface '{}' from bundled default: {}",
                missing.size, surfaceId.value, missing.keys.map { it.value },
            )
            layout.copy(slots = layout.slots + missing)
        }
        return if (changed) user.copy(surfaces = merged) else user
    }

    // Synchronous file ops. Caller MUST hold [mutex] when invoking.
    // The debounce coroutine acquires the mutex inside its launched
    // block; flush() invokes from inside its own mutex.withLock.
    private fun writeNow() {
        if (readOnly) {
            log.debug("Layout graph at {} is from a newer build -- skipping write-back", file)
            return
        }
        try {
            val envelope = Envelope(schemaVersion = SCHEMA_VERSION, graph = state.value)
            AtomicFiles.writeString(file, json.encodeToString(envelope))
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
        const val SCHEMA_VERSION = 4

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

    private const val CURRENT = 4

    private fun step(toVersion: Int): Step = when (toVersion) {
        // v1 -> v2: WidgetInstance gained a children field.
        // v2 -> v3: WidgetInstance gained a nullable chrome field.
        // Both add a defaulted field, so deserialization handles backward
        // compat and the steps are identity.
        2 -> Step.IDENTITY
        3 -> Step.IDENTITY
        // v3 -> v4: the nav rail unified onto the single nav.entry kind.
        4 -> Step(::migrateNavToEntries)
        else -> Step.IDENTITY
    }

    private fun interface Step {
        fun apply(graph: LayoutGraph): LayoutGraph
        companion object {
            val IDENTITY = Step { it }
        }
    }
}

// Schema v3 -> v4: the nav rail unified onto a single configurable kind,
// nav.entry. The retired kinds (the bundled navbuttons block, the per-item
// nav.* widgets, the console/logout buttons) render nothing once dropped
// from the registry, so a persisted leftrail must be rewritten or the rail
// goes blank with no in-product way back except a surface reset. Applied
// graph-wide so a retired nav widget dropped on any surface is converted
// too, not only the bundled leftrail.
private fun migrateNavToEntries(graph: LayoutGraph): LayoutGraph =
    graph.flatMapInstances { w ->
        when (w.kind.value) {
            "appshell.leftrail.navbuttons" -> NAVBUTTONS_TARGETS.map { (token, target) ->
                // Drop the block's chrome / weight / canvas: the monolith
                // painted six bare rail items under one frame, so a single
                // backing or weighted share must not replicate onto all six.
                // The id derives from the (unique) original, so the six stay
                // unique without the load-path uniqueness guard.
                WidgetInstance(
                    kind       = NAV_ENTRY,
                    instanceId = "${w.instanceId}-$token",
                    props      = navTargetProps(target),
                )
            }
            "appshell.leftrail.consoletoggle" -> listOf(w.toNavEntry("Console"))
            "appshell.leftrail.logout"        -> listOf(w.toNavEntry("Logout"))
            "nav.home"     -> listOf(w.toNavEntry("Home"))
            "nav.library"  -> listOf(w.toNavEntry("Library"))
            "nav.browse"   -> listOf(w.toNavEntry("Browse"))
            "nav.profile"  -> listOf(w.toNavEntry("Profile"))
            "nav.settings" -> listOf(w.toNavEntry("Settings"))
            "nav.about"    -> listOf(w.toNavEntry("About"))
            else           -> listOf(w)
        }
    }

// 1:1 conversion -- preserves chrome / weight / canvas, only kind + props change.
private fun WidgetInstance.toNavEntry(target: String): WidgetInstance =
    copy(kind = NAV_ENTRY, props = navTargetProps(target))

// Raw-string props: client-launcher cannot see the NavTarget enum (it lives
// in client-ui), and a Kotlin enum's default serial name equals its constant
// name, so these strings decode straight into NavTarget. A NavTarget rename
// would break the contract -- guarded by NavTargetSerialNameTest.
private fun navTargetProps(target: String): JsonObject =
    JsonObject(mapOf("target" to JsonPrimitive(target)))

private val NAV_ENTRY = WidgetKind("nav.entry")

// Top-to-bottom order of the retired monolith's six items.
private val NAVBUTTONS_TARGETS = listOf(
    "home" to "Home",
    "library" to "Library",
    "browse" to "Browse",
    "profile" to "Profile",
    "settings" to "Settings",
    "about" to "About",
)
