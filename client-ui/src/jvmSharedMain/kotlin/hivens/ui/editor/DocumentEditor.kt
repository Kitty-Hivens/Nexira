package hivens.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import hivens.ui.layout.DocumentStore
import hivens.widget.model.Document
import hivens.widget.model.FlowSpec
import hivens.widget.model.GRID_MAX
import hivens.widget.model.NodePath
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceInsets
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.ViewportSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.WidgetMotion
import hivens.widget.model.contentAt
import hivens.widget.model.edit
import hivens.widget.model.holdOwn
import hivens.widget.model.moveWidget
import hivens.widget.model.parseAnchor
import hivens.widget.model.placeInGrid
import hivens.widget.model.reordered
import hivens.widget.model.resizeInGrid
import hivens.widget.model.seedPlacement
import hivens.widget.model.shareOwn
import hivens.widget.model.withFlow
import hivens.widget.model.withGrid
import hivens.widget.model.withPlacement
import hivens.widget.model.withViewport
import hivens.widget.model.withWidget
import hivens.widget.model.withWidgetUpdated
import hivens.widget.model.withoutWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * The editor's one way to change the document: every edit is a transform of the document [store] draws, made on one
 * thread in the order it was asked for, read and written inside that one step, and taken back by [undo].
 *
 * The standard document is nobody's to edit. The first edit of it waits for the person's word ([consent]): yes makes
 * the document their own and the edit lands, no drops it, and nothing is written meanwhile. From then on updates of the
 * launcher no longer replace their document.
 *
 * Every write is fire and forget. The store draws an edit at once, so the caller never waits for one.
 */
class DocumentEditor(
    private val store: DocumentStore,
    private val scope: CoroutineScope,
    /** A name no widget has yet. */
    private val mint: (String) -> String = { "${it.substringBefore('#')}#${UUID.randomUUID().toString().take(8)}" },
) {
    // One thread, so the frames of a drag reach the store in the order they were made and a later frame is never
    // overwritten by an earlier one that won a race.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writes = Dispatchers.Default.limitedParallelism(1)

    // Touched only on [writes].
    private val history = EditHistory<Document>()
    private var recording = false

    private val _canUndo = mutableStateOf(false)
    private val _canRedo = mutableStateOf(false)
    val canUndo: Boolean get() = _canUndo.value
    val canRedo: Boolean get() = _canRedo.value

    /** The first edit of the standard document, waiting for the person's word, or null. */
    private val _consent = mutableStateOf<PendingEdit?>(null)
    val consent: State<PendingEdit?> get() = _consent

    /** An edit that waits for the document to be the person's own. */
    class PendingEdit internal constructor(internal val key: String?, internal val transform: (Document) -> Document)

    /** Whether edits are being made in a session that can take them back. The history lives and dies with it. */
    fun reportEditing(on: Boolean) {
        scope.launch(writes) {
            recording = on
            if (!on) history.clear()
            publish()
        }
    }

    /** Returns once every write asked for before the call has landed. For a caller that reads back what it wrote. */
    internal suspend fun awaitWrites() {
        withContext(writes) {}
    }

    /**
     * Changes the document by [transform], as one step of the history under [key], or under none for a step of its
     * own. A transform that changes nothing is no step.
     */
    fun edit(key: String? = null, transform: (Document) -> Document) {
        scope.launch(writes) { apply(key, transform) }
    }

    private fun apply(key: String?, transform: (Document) -> Document) {
        if (!store.own.value) {
            // Only the first one waits. What follows it while the question is open is dropped: the person has not
            // said yet that the document is theirs to change.
            if (_consent.value == null && transform(store.document.value) != store.document.value) _consent.value = PendingEdit(key, transform)
            return
        }
        val before = store.document.value
        if (!store.update(transform)) return
        val after = store.document.value
        if (recording) history.record(key, before, after)
        publish()
    }

    /** The person's word on [consent]: yes makes the document theirs and lands the waiting edit, no drops it. */
    fun answerConsent(yes: Boolean) {
        scope.launch(writes) {
            val pending = _consent.value ?: return@launch
            _consent.value = null
            if (!yes || !store.takeOwn()) return@launch
            apply(pending.key, pending.transform)
        }
    }

    /** Ends the step under way, so the next edit is a step of its own. Called when a gesture ends. */
    fun closeStep() {
        scope.launch(writes) { history.close() }
    }

    /** Steps back. Nothing to undo, or a store that does not take it, is a no-op. */
    fun undo() {
        scope.launch(writes) {
            val current = store.document.value
            val previous = history.undo(current) ?: return@launch
            if (!store.update { previous }) history.redo(previous)
            publish()
        }
    }

    /** Steps forward again, until the next edit branches away. */
    fun redo() {
        scope.launch(writes) {
            val current = store.document.value
            val next = history.redo(current) ?: return@launch
            if (!store.update { next }) history.undo(next)
            publish()
        }
    }

    private fun publish() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    // ── Widgets ──────────────────────────────────────────────────────

    /**
     * Puts a new widget of [kind] into the slot at [path], at [index] or at the end where that is past it, holding an
     * empty slot for each of [slots], which a container declares, so what is dropped into it has somewhere to land. A
     * slot that places its widgets gives it [placement], or the next free place where that is null. Index and place
     * are read from the slot as it is when the edit lands.
     */
    fun add(path: NodePath, kind: WidgetKind, slots: List<SlotId>, index: Int, placement: Placement? = null) {
        val id = mint(kind.value)
        edit { document ->
            document.edit(path) { content ->
                val seeded = placement ?: if (content.flow == null) seedPlacement(content.widgets.size, content.grid, content.widgets) else null
                val widget = WidgetInstance(kind = kind, instanceId = id, children = slots.associateWith { SlotContent() }, placement = seeded)
                content.withWidget(widget, index.coerceIn(0, content.widgets.size))
            }
        }
    }

    fun remove(path: NodePath, instanceId: String) = edit { it.edit(path) { c -> c.withoutWidget(instanceId) } }

    fun reorder(path: NodePath, fromIndex: Int, toIndex: Int) = edit { it.edit(path) { c -> c.reordered(fromIndex, toIndex) } }

    fun move(from: NodePath, to: NodePath, instanceId: String, toIndex: Int) = edit { it.moveWidget(from, to, instanceId, toIndex) }

    /**
     * Changes a widget's props from what they are when the edit lands, so a slider and a toggle writing the same record
     * never put back what the other changed. [key] folds a run of writes to one field into one step.
     */
    fun props(path: NodePath, instanceId: String, key: String, transform: (JsonObject) -> JsonObject) =
        edit("props:$instanceId:$key") { it.edit(path) { c -> c.withWidgetUpdated(instanceId) { w -> w.copy(props = transform(w.props)) } } }

    /**
     * Changes a widget's own plane from what it is when the edit lands, or from [declared], the plane its widget
     * declares, where it has none of its own yet. A plane equal to [declared] is none of its own: the widget follows
     * its declaration again.
     */
    fun surface(path: NodePath, instanceId: String, key: String, declared: SurfaceSpec?, transform: (SurfaceSpec) -> SurfaceSpec?) =
        edit("surface:$instanceId:$key") {
            it.edit(path) { c ->
                c.withWidgetUpdated(instanceId) { w ->
                    val next = transform(w.surface ?: declared ?: SurfaceSpec())
                    w.copy(surface = next?.takeUnless { s -> s == declared || (declared == null && s == SurfaceSpec()) })
                }
            }
        }

    /** Changes a widget's own arrival from what it is when the edit lands. One that says nothing is none. */
    fun motion(path: NodePath, instanceId: String, key: String, transform: (WidgetMotion) -> WidgetMotion) =
        edit("motion:$instanceId:$key") {
            it.edit(path) { c ->
                c.withWidgetUpdated(instanceId) { w -> w.copy(motion = transform(w.motion ?: WidgetMotion()).takeUnless { m -> m.isEmpty }) }
            }
        }

    /** Changes the room kept around a widget from what it is when the edit lands. */
    fun padding(path: NodePath, instanceId: String, key: String, transform: (SurfaceInsets) -> SurfaceInsets) =
        placement(path, instanceId, "padding:$key") { it.copy(padding = transform(it.padding)) }

    /** Puts a widget back to what its declaration says, props, plane, arrival and room around it, in one step. */
    fun reset(path: NodePath, instanceId: String) = edit {
        it.edit(path) { c ->
            c.withWidgetUpdated(instanceId) { w ->
                w.copy(props = JsonObject(emptyMap()), surface = null, motion = null, placement = w.placement?.copy(padding = SurfaceInsets()))
            }
        }
    }

    // ── Placement ────────────────────────────────────────────────────

    /** Changes a widget's placement from what it is when the edit lands, under [key] for one step per gesture. */
    fun placement(path: NodePath, instanceId: String, key: String, transform: (Placement) -> Placement) =
        edit("$key:$instanceId") { it.edit(path) { c -> c.withPlacement(instanceId, transform) } }

    fun offset(path: NodePath, instanceId: String, x: Float, y: Float) = placement(path, instanceId, "offset") { it.copy(x = x, y = y) }

    fun size(path: NodePath, instanceId: String, width: Float, height: Float) =
        placement(path, instanceId, "size") { it.copy(width = width.coerceAtLeast(0f), height = height.coerceAtLeast(0f)) }

    /** Offset and size together, which is what dragging a leading edge changes: one step, one write per frame. */
    fun bounds(path: NodePath, instanceId: String, x: Float, y: Float, width: Float, height: Float) =
        placement(path, instanceId, "bounds") { it.copy(x = x, y = y, width = width.coerceAtLeast(0f), height = height.coerceAtLeast(0f)) }

    fun z(path: NodePath, instanceId: String, z: Int) = placement(path, instanceId, "z") { it.copy(z = z) }

    fun pinned(path: NodePath, instanceId: String, pinned: Boolean) = edit { it.edit(path) { c -> c.withPlacement(instanceId) { p -> p.copy(pinned = pinned) } } }

    /** Attaches the widget to a corner and starts it there: the offset counted from the old corner. */
    fun anchor(path: NodePath, instanceId: String, anchor: String) = edit {
        it.edit(path) { c ->
            c.withPlacement(instanceId) { p ->
                val next = parseAnchor(anchor)
                if (next == parseAnchor(p.anchor)) p else p.copy(anchor = next, x = 0f, y = 0f)
            }
        }
    }

    /**
     * Puts a widget's placement back to [placement], what it was when a gesture that writes as it goes began, because
     * the gesture was cut short. The step the gesture made goes with it, so undo does not lead back into the move that
     * was abandoned.
     */
    fun restorePlacement(path: NodePath, instanceId: String, placement: Placement?) {
        scope.launch(writes) {
            val before = store.document.value
            if (!store.update { d -> d.edit(path) { c -> c.withWidgetUpdated(instanceId) { w -> if (w.placement == placement) w else w.copy(placement = placement) } } }) return@launch
            if (recording) history.recordRestore(before, store.document.value)
            publish()
        }
    }

    /** Moves a widget to a cell of a lattice, snapping to the nearest free one, keeping its span. */
    fun moveInGrid(path: NodePath, instanceId: String, col: Int, row: Int, columns: Int) = edit("grid:$instanceId") {
        it.edit(path) { c ->
            val current = c.widgets.firstOrNull { w -> w.instanceId == instanceId } ?: return@edit c
            placeInGrid(c, instanceId, (current.placement ?: Placement()).copy(x = col.toFloat(), y = row.toFloat()), columns)
        }
    }

    /** Grows a widget's span in a lattice from its own anchor, never over a neighbour. */
    fun resizeInGrid(path: NodePath, instanceId: String, colSpan: Int, rowSpan: Int, columns: Int) = edit("gridsize:$instanceId") {
        it.edit(path) { c -> if (c.widgets.none { w -> w.instanceId == instanceId }) c else resizeInGrid(c, instanceId, colSpan.toFloat(), rowSpan.toFloat(), columns) }
    }

    // ── Slots ────────────────────────────────────────────────────────

    fun flow(path: NodePath, flow: FlowSpec?) = edit { it.edit(path) { c -> c.withFlow(flow) } }

    fun viewport(path: NodePath, viewport: ViewportSpec?) = edit { it.edit(path) { c -> c.withViewport(viewport) } }

    /** Steps the line length of a wrapped flow, read when the edit lands so quick presses add up. */
    fun nudgeWrap(path: NodePath, delta: Int) = edit("wrap:$path") {
        it.edit(path) { c -> c.flow?.let { f -> c.withFlow(f.copy(wrap = (f.wrap + delta).coerceIn(0, GRID_MAX))) } ?: c }
    }

    /** Steps the lattice a placement slot measures in. 0 is free placement. */
    fun nudgeGrid(path: NodePath, delta: Int) = edit("grid:$path") { it.edit(path) { c -> c.withGrid(c.grid + delta) } }

    /** Makes the slot at [path], a slot of a widget the composition has once, this config's own. */
    fun holdOwn(path: NodePath) = edit { it.holdOwn(path, mint) }

    /** Gives the slot at [path] back to the record every config shares, dropping what this config held there. */
    fun shareOwn(path: NodePath) = edit { it.shareOwn(path) }

    /** What the slot at [path] holds now, for a caller deciding what to ask for. */
    fun contentAt(path: NodePath): SlotContent? = store.document.value.contentAt(path)
}
