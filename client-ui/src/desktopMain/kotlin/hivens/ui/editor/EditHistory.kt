package hivens.ui.editor

import hivens.widget.model.LayoutGraph

/**
 * What the editor has done, so it can be taken back.
 *
 * Snapshots rather than inverse operations. [LayoutGraph] is immutable and every
 * transform rebuilds only the path it touched, so a snapshot shares almost all of
 * its structure with the one before it and costs a handful of objects. Writing an
 * inverse for each of the seventeen mutations would cost far more and be wrong in
 * exactly the cases nobody tests, which is the pair that does not round-trip.
 *
 * ## A drag is one step
 *
 * Free placement writes an offset on every frame of a drag, so a gesture that
 * lasts a second arrives here sixty times. Undo has to take the whole gesture
 * back, not a sixtieth of it, or the key has to be held down to escape one drag
 * and the feature is worse than useless.
 *
 * So a run of edits carrying the same [key] within [COALESCE_MS] of each other is
 * one entry, and the entry holds the graph the run STARTED at. The key is the
 * caller's to choose: per widget and per axis, so dragging one widget and then
 * another does not merge into a single step. A null key never coalesces, which is
 * what a structural edit wants -- adding two widgets is two things a person did.
 *
 * The window is longer than the repository's own write debounce (200ms) on
 * purpose: a gesture that pauses long enough to be persisted separately is long
 * enough to be taken back separately.
 */
class EditHistory(
    private val limit: Int = LIMIT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val past = ArrayDeque<LayoutGraph>()
    private val future = ArrayDeque<LayoutGraph>()

    private var openKey: String? = null
    private var openAt = 0L

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val depth: Int get() = past.size

    /**
     * Notes that [before] became [after].
     *
     * A transform the repository refused, or one that changed nothing, is not a
     * step: undoing it would look like the key had done nothing, which is how a
     * person concludes undo is broken.
     */
    fun record(key: String?, before: LayoutGraph, after: LayoutGraph) {
        if (before == after) return
        val at = now()
        val continues = key != null && key == openKey && at - openAt <= COALESCE_MS && past.isNotEmpty()
        openKey = key
        openAt = at
        // The open entry already holds the graph this run began at, so a
        // continuing frame adds nothing but depth.
        if (continues) return
        // Anything done after an undo is a new branch, and the old future is no
        // longer reachable from it.
        future.clear()
        past.addLast(before)
        while (past.size > limit) past.removeFirst()
    }

    /** The graph to go back to, or null when there is nothing to take back. */
    fun undo(current: LayoutGraph): LayoutGraph? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        // A gesture cannot continue across an undo: the next edit starts its own
        // entry even if it carries the key the last one did.
        openKey = null
        return previous
    }

    /** The graph an undo came from, or null when nothing was undone. */
    fun redo(current: LayoutGraph): LayoutGraph? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        openKey = null
        return next
    }

    /** Drops everything, for a reset that is not meant to be stepped back through. */
    fun clear() {
        past.clear()
        future.clear()
        openKey = null
    }

    private companion object {
        /**
         * How far back the editor remembers. Sixty-four snapshots of a structurally
         * shared tree is a few hundred kilobytes at the outside, and further back
         * than anyone reaches by pressing a key.
         */
        const val LIMIT = 64

        /** Longer than the repository's write debounce, for the reason in the class doc. */
        const val COALESCE_MS = 400L
    }
}
