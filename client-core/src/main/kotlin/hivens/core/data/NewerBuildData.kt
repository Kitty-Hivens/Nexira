package hivens.core.data

import java.util.Collections

/** A store this build reads but will not write back. */
enum class ReadOnlyStore { PackLibrary, Layout }

/**
 * Stores that were written by a NEWER build than this one and are therefore open
 * read-only for the session.
 *
 * Both stores make that call on their own, for the same reason: the file carries
 * a schema this build does not fully understand, and writing it back would
 * discard whatever it cannot represent. Refusing the write is right. Doing it
 * silently is not -- the launcher keeps accepting edits, shows them for the whole
 * session, and drops them at exit.
 *
 * The stores record here at load, and the shell reads it once to say so. A
 * process-global rather than a value threaded through the graph because the fact
 * is decided during construction, in two modules, before anything that could
 * carry it exists -- the same shape as the other boot-time facts.
 */
object NewerBuildData {

    private val stores: MutableSet<ReadOnlyStore> =
        Collections.synchronizedSet(LinkedHashSet())

    /** Called by a store that has just refused to write itself back. */
    fun record(store: ReadOnlyStore) {
        stores.add(store)
    }

    /** Every store open read-only, in the order they were found. */
    fun affected(): Set<ReadOnlyStore> = synchronized(stores) { LinkedHashSet(stores) }

    /** Test seam: nothing in the app clears this, a session decides it once. */
    fun reset() {
        stores.clear()
    }
}
