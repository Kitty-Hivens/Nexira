package hivens.ui.layout

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
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
        const val SCHEMA_VERSION = LayoutReconcile.CURRENT_SCHEMA

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

    private const val CURRENT = LayoutReconcile.CURRENT_SCHEMA

    private fun step(toVersion: Int): Step = when (toVersion) {
        // v1 -> v2: WidgetInstance gained a children field.
        // v2 -> v3: WidgetInstance gained a nullable chrome field.
        // Both add a defaulted field, so deserialization handles backward
        // compat and the steps are identity.
        2 -> Step.IDENTITY
        3 -> Step.IDENTITY
        // v3 -> v4: the nav rail unified onto the single nav.entry kind.
        4 -> Step(::migrateNavToEntries)
        // v4 -> v5: the Wardrobe (skins) nav entry joined the bundled rail.
        5 -> Step(::insertWardrobeNavEntry)
        // v5 -> v6: the shell gained a top bar. Relocate the three regions under
        // a new appshell.body sub-surface and stack the bar over them.
        6 -> Step(::migrateShellAddTopBar)
        // v6 -> v7: the new home's default swapped quicklaunch for the
        // art-backed hero and turned the welcome subtitle off.
        7 -> Step(::migrateHomeHero)
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
                // unique; the load-path sweep backstops the case where a
                // derived id still clashes with a pre-existing one.
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

// Schema v4 -> v5: the Wardrobe (skins) nav entry was added to the bundled rail.
// Existing rails pre-date it, and the reconciler only seeds whole missing slots,
// not new widgets inside a slot the user already has -- so inject it once, right
// after the Profile entry (its bundled position). Idempotent: a graph that already
// carries a Wardrobe entry is left as-is; a rail with no Profile entry (heavily
// customised) is skipped rather than guessed at.
private fun insertWardrobeNavEntry(graph: LayoutGraph): LayoutGraph {
    if (graph.walkInstances().any { it.kind == NAV_ENTRY && navTarget(it) == "Wardrobe" }) return graph
    var inserted = false
    return graph.flatMapInstances { w ->
        if (!inserted && w.kind == NAV_ENTRY && navTarget(w) == "Profile") {
            inserted = true
            listOf(
                w,
                WidgetInstance(
                    kind = NAV_ENTRY,
                    instanceId = "appshell-leftrail-nav-wardrobe",
                    props = navTargetProps("Wardrobe"),
                ),
            )
        } else {
            listOf(w)
        }
    }
}

private fun navTarget(w: WidgetInstance): String? = (w.props["target"] as? JsonPrimitive)?.content

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

// Schema v5 -> v6: the shell root gained a top bar. The reconciler only ADDS
// missing surfaces/slots; it never reshapes an existing slot, so a persisted
// appshell.root/regions (the old Row of three regions) would keep the old shape
// and never show the bar. Relocate whatever the user has in regions into the new
// appshell.body sub-surface (props / weight / chrome preserved -- a heavily
// customised rail is moved, not dropped), and replace regions with the Column of
// [top, body]. appshell.body is created HERE so mergeMissingSurfaces won't seed
// the bundled default body over the moved regions; appshell.topbar is left to the
// merge to seed from the default (it carries no user data yet).
private fun migrateShellAddTopBar(graph: LayoutGraph): LayoutGraph {
    val root = graph.surfaces[SHELL_ROOT_SURFACE] ?: return graph
    val regions = root.slots[REGIONS_SLOT] ?: return graph
    // Idempotent: a graph already carrying the top region is left untouched.
    if (regions.widgets.any { it.kind == REGION_TOP_KIND }) return graph

    val body = SurfaceLayout(
        slots = mapOf(
            BODY_CONTENT_SLOT to SlotContent(widgets = regions.widgets, orientation = SlotOrientation.Row),
        ),
    )
    val newRegions = SlotContent(
        orientation = SlotOrientation.Column,
        widgets = listOf(
            WidgetInstance(kind = REGION_TOP_KIND, instanceId = "appshell-region-top-default"),
            WidgetInstance(kind = REGION_BODY_KIND, instanceId = "appshell-region-body-default", weight = 1f),
        ),
    )
    val newRoot = root.copy(slots = root.slots + (REGIONS_SLOT to newRegions))
    return graph.copy(surfaces = graph.surfaces + (SHELL_ROOT_SURFACE to newRoot) + (SHELL_BODY_SURFACE to body))
}

private val SHELL_ROOT_SURFACE = SurfaceId("appshell.root")
private val SHELL_BODY_SURFACE = SurfaceId("appshell.body")
private val REGIONS_SLOT       = SlotId("regions")
private val BODY_CONTENT_SLOT  = SlotId("content")
private val REGION_TOP_KIND    = WidgetKind("appshell.region.top")
private val REGION_BODY_KIND   = WidgetKind("appshell.region.body")

// Schema v6 -> v7: the new home's default replaced the quicklaunch block with
// the art-backed hero card and switched the welcome subtitle (permanent
// onboarding copy) off. Applies ONLY while home.new/main still holds the
// untouched v6 default -- the exact four bundled instance ids in their
// bundled order. Any customised slot keeps the user's arrangement byte for
// byte (the hero stays available from the editor palette). The kept
// instances carry their existing props over; the welcome only gains
// showSubtitle=false when the user never set that key themselves.
private fun migrateHomeHero(graph: LayoutGraph): LayoutGraph {
    val home = graph.surfaces[HOME_NEW_SURFACE] ?: return graph
    val main = home.slots[HOME_MAIN_SLOT] ?: return graph
    if (main.widgets.map { it.instanceId } != HOME_V6_DEFAULT_IDS) return graph

    val byId = main.widgets.associateBy { it.instanceId }
    val welcome = byId.getValue("home-new-welcome-default").let { w ->
        if ("showSubtitle" in w.props) w
        else w.copy(props = JsonObject(w.props + ("showSubtitle" to JsonPrimitive(false))))
    }
    val newMain = main.copy(
        widgets = listOf(
            welcome,
            byId.getValue("home-new-spacer-default"),
            WidgetInstance(kind = HOME_HERO_KIND, instanceId = "home-new-hero-default"),
            byId.getValue("home-new-recent-default"),
        ),
    )
    val newHome = home.copy(slots = home.slots + (HOME_MAIN_SLOT to newMain))
    return graph.copy(surfaces = graph.surfaces + (HOME_NEW_SURFACE to newHome))
}

private val HOME_NEW_SURFACE = SurfaceId("home.new")
private val HOME_MAIN_SLOT   = SlotId("main")
private val HOME_HERO_KIND   = WidgetKind("home.new.hero")
private val HOME_V6_DEFAULT_IDS = listOf(
    "home-new-welcome-default",
    "home-new-spacer-default",
    "home-new-recent-default",
    "home-new-quicklaunch-default",
)
