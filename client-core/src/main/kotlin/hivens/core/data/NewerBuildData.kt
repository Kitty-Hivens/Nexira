package hivens.core.data

import java.util.Collections

/** A store this build reads but will not write back. */
enum class ReadOnlyStore { PackLibrary, Layout, Theme }

/**
 * Why a store is open read-only. The refusal is the same either way; what the
 * reader should do about it is not, so the two cannot share one sentence.
 */
enum class ReadOnlyReason {
    /** Written by a newer build. Updating makes it writable again. */
    NewerBuild,

    /**
     * Written in a form this build has no faithful reading of, old enough that
     * migrating it would guess. Updating does not help and going back does, so
     * the file is left exactly as it is.
     */
    UnreadableFormat,
}

/**
 * Stores that are open read-only for the session.
 *
 * The stores make that call on their own, for the same reason in both
 * directions: the file carries a schema this build does not fully understand,
 * and writing it back would discard whatever it cannot represent. Refusing the
 * write is right. Doing it silently is not -- the launcher keeps accepting
 * edits, shows them for the whole session, and drops them at exit.
 *
 * The stores record here at load, and the shell reads it once to say so. A
 * process-global rather than a value threaded through the graph because the fact
 * is decided during construction, in three modules, before anything that could
 * carry it exists -- the same shape as the other boot-time facts.
 */
object NewerBuildData {

    private val stores: MutableMap<ReadOnlyStore, ReadOnlyReason> =
        Collections.synchronizedMap(LinkedHashMap())

    /**
     * Called by a store that has just refused to write itself back.
     *
     * The reason defaults to [ReadOnlyReason.NewerBuild] because that was the
     * only case when this existed, and every caller that predates the second one
     * means it.
     */
    fun record(store: ReadOnlyStore, reason: ReadOnlyReason = ReadOnlyReason.NewerBuild) {
        stores[store] = reason
    }

    /** Every store open read-only, in the order they were found. */
    fun affected(): Set<ReadOnlyStore> = synchronized(stores) { LinkedHashSet(stores.keys) }

    /** Every store open read-only with why, in the order they were found. */
    fun affectedWithReason(): Map<ReadOnlyStore, ReadOnlyReason> =
        synchronized(stores) { LinkedHashMap(stores) }

    /** Test seam: nothing in the app clears this, a session decides it once. */
    fun reset() {
        stores.clear()
    }
}
