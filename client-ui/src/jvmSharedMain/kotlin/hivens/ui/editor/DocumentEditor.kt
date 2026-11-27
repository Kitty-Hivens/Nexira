package hivens.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import hivens.ui.layout.DocumentStore
import hivens.pages.api.MadeScreen
import hivens.pages.api.ScreenId
import hivens.pages.api.pagesHost
import hivens.pages.api.pagesHostProps
import hivens.pages.api.screenContent
import hivens.pages.api.withMadeScreen
import hivens.pages.api.withScreenContent
import hivens.pages.api.withScreenNamed
import hivens.pages.api.withoutMadeScreen
import hivens.ui.screens.custom.ScreenLinks
import hivens.widget.model.CompositionName
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
import hivens.widget.model.flatMapWidgets
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
import hivens.ui.widgets.state.instanceIds
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("DocumentEditor")

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
    /** Where a widget may stand. A drop or a move it refuses leaves the document as it was. */
    private val admission: DocumentAdmission = DocumentAdmission.Open,
    /** A name no widget has yet. */
    private val mint: (String) -> String = { "${it.substringBefore('#')}#${UUID.randomUUID().toString().take(8)}" },
    /** Gives a widget copied under a new name the state of the one it was copied from. */
    private val copyState: (from: String, to: String) -> Unit = { _, _ -> },
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
    class PendingEdit internal constructor(
        internal val key: String?,
        internal val transform: (Document) -> Document,
        internal val landed: ((before: Document, after: Document) -> Unit)?,
    )

    private val _editing = mutableStateOf(false)

    /** Whether somebody is arranging now, which is when the window's undo and redo are the editor's. */
    val isEditing: Boolean get() = _editing.value

    /** Whether edits are being made in a session that can take them back. The history lives and dies with it. */
    fun reportEditing(on: Boolean) {
        _editing.value = on
        scope.launch(writes) {
            recording = on
            if (!on) history.clear()
            publish()
        }
    }

    // The screens deleted this session, kept so their widgets keep their state while the screen can still be put back.
    // Touched only on [writes].
    private val deleted = HashMap<ScreenId, DeletedScreen>()

    /**
     * The names of the widgets something here can still bring back: the documents undo and redo reach and the screens
     * deleted this session. Their state is kept although the document drawn does not hold them.
     *
     * The document as it is now too, read in the same step as the history. A collector holding a document it sampled
     * a moment ago would otherwise miss a widget an undo brought back in between: no longer in the history, not yet
     * in what it sampled.
     */
    suspend fun heldIds(): Set<String> = withContext(writes) {
        val ids = HashSet(store.document.value.instanceIds())
        history.snapshots().forEach { ids += it.instanceIds() }
        deleted.values.forEach { ids += it.content.instanceIds() }
        ids
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

    /** Lands [transform], and once it has landed tells [landed] what the document was and became. */
    private fun apply(key: String?, transform: (Document) -> Document, landed: ((Document, Document) -> Unit)? = null) {
        droppedByUndo = false
        if (!store.own.value) {
            // Only the first one waits. What follows it while the question is open is dropped: the person has not
            // said yet that the document is theirs to change.
            if (_consent.value == null && transform(store.document.value) != store.document.value) _consent.value = PendingEdit(key, transform, landed)
            return
        }
        val before = store.document.value
        if (!store.update(transform)) return
        val after = store.document.value
        if (recording) history.record(key, before, after)
        publish()
        if (before != after) landed?.invoke(before, after)
    }

    /** The person's word on [consent]: yes makes the document theirs and lands the waiting edit, no drops it. */
    fun answerConsent(yes: Boolean) {
        scope.launch(writes) {
            val pending = _consent.value ?: return@launch
            _consent.value = null
            if (!yes || !store.takeOwn()) return@launch
            apply(pending.key, pending.transform, pending.landed)
        }
    }

    /** Ends the step under way, so the next edit is a step of its own. Called when a gesture ends. */
    fun closeStep() {
        scope.launch(writes) { history.close() }
    }

    // Whether the last step back made the document the standard one again and gave it up, so a step forward takes it
    // back without asking. Touched only on [writes].
    private var droppedByUndo = false

    /**
     * Steps back. Nothing to undo, or a store that does not take it, is a no-op. A step back that leaves the person's own
     * document the same as the standard one gives it up without a word: nothing of theirs is left in it, and updates
     * reach the interface again.
     */
    fun undo() {
        scope.launch(writes) {
            val current = store.document.value
            val previous = history.undo(current) ?: return@launch
            if (!store.update { previous }) history.redo(previous)
            else if (store.matchesStandard && store.dropOwn()) droppedByUndo = true
            publish()
        }
    }

    /** Steps forward again, until the next edit branches away. One out of the standard document a step back left takes it back. */
    fun redo() {
        scope.launch(writes) {
            if (droppedByUndo && !store.own.value) store.takeOwn()
            droppedByUndo = false
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
            if (refuses(kind.value, admission.missing(kind, path, document), path)) return@edit document
            document.edit(path) { content ->
                val seeded = placement ?: if (content.flow == null) seedPlacement(content.widgets.size, content.grid, content.widgets) else null
                val widget = WidgetInstance(kind = kind, instanceId = id, children = slots.associateWith { SlotContent() }, placement = seeded)
                content.withWidget(widget, index.coerceIn(0, content.widgets.size))
            }
        }
    }

    fun remove(path: NodePath, instanceId: String) = edit { it.edit(path) { c -> c.withoutWidget(instanceId) } }

    fun reorder(path: NodePath, fromIndex: Int, toIndex: Int) = edit { it.edit(path) { c -> c.reordered(fromIndex, toIndex) } }

    fun move(from: NodePath, to: NodePath, instanceId: String, toIndex: Int) = edit { document ->
        val moved = document.contentAt(from)?.widgets?.firstOrNull { it.instanceId == instanceId }
        if (moved != null && refuses(moved.kind.value, admission.missing(moved, to, document), to)) document
        else document.moveWidget(from, to, instanceId, toIndex)
    }

    /** What the slot at [path] would not answer for a widget, or nothing. For the host to show on a drag. */
    fun missingAt(path: NodePath, kind: WidgetKind): List<String> = admission.missing(kind, path, store.document.value)

    fun missingAt(path: NodePath, widget: WidgetInstance): List<String> = admission.missing(widget, path, store.document.value)

    // A refusal is not an error: the host offered a drop the widget cannot take, and the document stays as it was.
    // Logged, because a drop that does nothing and says nothing is otherwise impossible to tell from one that was lost.
    private fun refuses(kind: String, missing: List<String>, path: NodePath): Boolean {
        if (missing.isEmpty()) return false
        log.info("Not placing {} at {}: nothing there answers {}", kind, path, missing)
        return true
    }

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

    /**
     * Makes the slot at [path], a slot of a widget the composition has once, this config's own. What it held is copied
     * under new names, and each copy keeps the state of the widget it was copied from.
     */
    fun holdOwn(path: NodePath) {
        scope.launch(writes) {
            apply(null, { it.holdOwn(path, mint) }) { before, after ->
                val shared = before.contentAt(path) ?: return@apply
                val own = after.contentAt(path) ?: return@apply
                copiedNames(shared, own).forEach { (from, to) -> copyState(from, to) }
            }
        }
    }

    /** Gives the slot at [path] back to the record every config shares, dropping what this config held there. */
    fun shareOwn(path: NodePath) = edit { it.shareOwn(path) }

    // ── Screens ──────────────────────────────────────────────────────

    /**
     * Makes a screen of [composition] called [title] and wearing [icon], in the pages host's record, with nothing on it.
     * The rail lists it among the screens made. Returns its id at once, before the edit lands, so the caller can open it
     * once it is there.
     */
    fun createScreen(composition: CompositionName, title: String, icon: String): ScreenId {
        val id = ScreenId(mint("screen"))
        edit { d -> d.withMadeScreen(composition, MadeScreen(id.value, title, icon)) }
        return id
    }

    /**
     * Renames a screen or changes its icon, one made or one a module brought. Null leaves that half as it is, blank puts
     * back what the screen has without the person. Typing a name is one step.
     */
    fun updateScreen(composition: CompositionName, id: ScreenId, title: String? = null, icon: String? = null) =
        edit("screen:${id.value}") { d -> d.withScreenNamed(composition, id, title, icon) }

    /**
     * Deletes a screen somebody made, what it held and every link to it. [onDeleted] hears what was deleted, as it stood,
     * for [restoreScreen]. A screen a module brought is the module's, and comes back with a reset instead.
     */
    fun deleteScreen(composition: CompositionName, id: ScreenId, onDeleted: (DeletedScreen) -> Unit = {}) {
        scope.launch(writes) {
            val host = store.document.value.compositions[composition]?.pagesHost() ?: return@launch
            val made = host.pagesHostProps().made.firstOrNull { it.id == id.value } ?: return@launch
            val gone = DeletedScreen(made, host.screenContent(id) ?: SlotContent())
            apply(null, { d ->
                d.withoutMadeScreen(composition, id).flatMapWidgets(composition) { w -> if (ScreenLinks.target(w) == id.value) emptyList() else listOf(w) }
            })
            val still = store.document.value.compositions[composition]?.pagesHost()?.pagesHostProps()?.made.orEmpty()
            if (still.none { it.id == id.value }) {
                deleted[id] = gone
                onDeleted(gone)
            }
        }
    }

    /** Puts a deleted screen back as it stood. */
    fun restoreScreen(composition: CompositionName, gone: DeletedScreen) =
        edit { d -> d.withMadeScreen(composition, gone.screen, gone.content) }

    // ── Presets ──────────────────────────────────────────────────────

    /**
     * Puts [preset] in place of the whole interface, keeping the modules the launcher has. One step, like any other.
     * [landed] hears once the interface is the preset's, already or by this edit, and never where the edit was dropped:
     * the person said no to making the document theirs, or a newer build's file is not written over.
     */
    fun applyPreset(preset: Document, landed: () -> Unit = {}) {
        scope.launch(writes) {
            val transform = { current: Document -> preset.copy(modules = current.modules) }
            if (transform(store.document.value) == store.document.value) landed()
            else apply(null, transform) { _, _ -> landed() }
        }
    }

    // ── Resets ───────────────────────────────────────────────────────

    /**
     * Puts the screen [id] back as [standard] has it: what the host holds for it, its name and its icon. A screen
     * somebody made, which the standard document does not have, keeps its name and loses what is on it.
     */
    fun resetScreen(composition: CompositionName, id: ScreenId, standard: Document) = edit { d ->
        val host = d.compositions[composition]?.pagesHost() ?: return@edit d
        if (host.pagesHostProps().made.any { it.id == id.value }) {
            d.withScreenContent(composition, id, SlotContent())
        } else {
            val shipped = standard.compositions[composition]?.pagesHost()?.screenContent(id)
            d.withScreenContent(composition, id, shipped).withScreenNamed(composition, id, title = "", icon = "")
        }
    }

    /**
     * Puts everything back as [standard] has it, the frame and the modules included. The screens somebody made stay,
     * with what is on them, so the document stays the person's own. One step, like any other.
     */
    fun resetAll(standard: Document) = edit { d ->
        standard.compositions.keys.fold(standard) { acc, name ->
            val host = d.compositions[name]?.pagesHost() ?: return@fold acc
            host.pagesHostProps().made.fold(acc) { a, made -> a.withMadeScreen(name, made, host.screenContent(ScreenId(made.id)) ?: SlotContent()) }
        }
    }

    /**
     * Gives up the person's own document: the standard one is drawn again and each update replaces it again. The screens
     * they made go with it. Not a step: nothing of theirs is left to step back into.
     */
    fun backToStandard() {
        scope.launch(writes) {
            if (!store.dropOwn()) return@launch
            history.clear()
            publish()
        }
    }

    /** What the slot at [path] holds now, for a caller deciding what to ask for. */
    fun contentAt(path: NodePath): SlotContent? = store.document.value.contentAt(path)
}

/** A screen somebody made, as it stood when it was deleted: its entry in the host's record and what it held. */
class DeletedScreen internal constructor(val screen: MadeScreen, internal val content: SlotContent)

/** Each widget of [from] paired with the one standing in its place in [to], where the two go by different names. */
internal fun copiedNames(from: SlotContent, to: SlotContent): List<Pair<String, String>> =
    from.widgets.zip(to.widgets).flatMap { (a, b) ->
        val here = if (a.kind == b.kind && a.instanceId != b.instanceId) listOf(a.instanceId to b.instanceId) else emptyList()
        here + a.children.flatMap { (slot, held) -> b.children[slot]?.let { copiedNames(held, it) }.orEmpty() }
    }

/** Every widget's name in [this] content, what they hold included. */
internal fun SlotContent.instanceIds(): Set<String> {
    val ids = HashSet<String>()
    fun walk(c: SlotContent) { c.widgets.forEach { ids += it.instanceId; it.children.values.forEach(::walk) } }
    walk(this)
    return ids
}
