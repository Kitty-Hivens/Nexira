package hivens.ui.editor

/**
 * What the editor has done, so it can be taken back.
 *
 * Snapshots of [T], what is edited, rather than inverse operations. The document is
 * immutable and every edit rebuilds only the path it touched, so a snapshot shares
 * almost all of its structure with the one before it and costs a handful of objects.
 * Writing an inverse for each kind of edit would cost far more and be wrong in
 * exactly the cases nobody tests, which is the pair that does not round-trip.
 *
 * ## A drag is one step
 *
 * Free placement writes an offset on every frame of a drag, so a gesture that
 * lasts a second arrives here sixty times. Undo has to take the whole gesture
 * back, not a sixtieth of it, or the key has to be held down to escape one drag
 * and the feature is worse than useless.
 *
 * So a run of edits carrying the same [key] is one entry until something closes it,
 * and the entry holds what the run STARTED at. The key is the caller's to choose:
 * per widget and per axis, so dragging one widget and then another does not merge
 * into a single step. A gesture closes its run when it ends ([close]), so two drags
 * of one widget are two steps however quickly the second follows. A null key never
 * coalesces, which is what a structural edit wants -- adding two widgets is two
 * things a person did.
 */
class EditHistory<T : Any>(private val limit: Int = LIMIT) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    private var openKey: String? = null

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
    fun record(key: String?, before: T, after: T) {
        if (before == after) return
        val continues = key != null && key == openKey && past.isNotEmpty()
        openKey = key
        // The open entry already holds what this run began at, so a
        // continuing frame adds nothing but depth.
        if (continues) return
        // Anything done after an undo is a new branch, and the old future is no
        // longer reachable from it.
        future.clear()
        past.addLast(before)
        while (past.size > limit) past.removeFirst()
    }

    /**
     * Notes that [before] became [after] by putting back what the last step had
     * changed, the way a gesture cut short puts its widget back where it started.
     *
     * When [after] is the graph that step started from, the step goes with it: a
     * gesture that came to nothing leaves nothing to undo. Anything else is
     * recorded like an edit of its own, because the graph did not end up where any
     * step began and undoing has to be able to reach it.
     */
    fun recordRestore(before: T, after: T) {
        if (before == after) return
        if (past.lastOrNull() == after) {
            past.removeLast()
            openKey = null
            return
        }
        record(key = null, before = before, after = after)
    }

    /** Ends the run under way, so the next edit is a step of its own whatever its key. */
    fun close() {
        openKey = null
    }

    /** What to go back to, or null when there is nothing to take back. */
    fun undo(current: T): T? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        // A gesture cannot continue across an undo: the next edit starts its own
        // entry even if it carries the key the last one did.
        openKey = null
        return previous
    }

    /** What an undo came from, or null when nothing was undone. */
    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        openKey = null
        return next
    }

    /** Every snapshot undo or redo can still reach. */
    fun snapshots(): List<T> = past + future

    /** Drops everything, for an edit session that has ended and cannot be stepped back into. */
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
    }
}
