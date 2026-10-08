package hivens.ui.screens.browse

import hivens.core.api.dto.modrinth.ModrinthSearchHit
import hivens.launcher.modrinth.SearchFilter

/**
 * What project searches have shown, kept for the life of the process.
 *
 * A search lives in the composition, and the composition goes every time the
 * reader opens a project from the list and comes back, or flips to another kind and
 * back. Each return started the search over: the first page asked for again, what
 * the pack holds asked for again, and the place in the list lost. Packs already
 * came back where they were left through [BrowseSession]; this is the same for
 * mods, resource packs, shaders and the catalogue's modpacks.
 *
 * A snapshot is taken as current for [FRESH_MS], the time the catalogue's search
 * pages are cached for, and asked again after that, so a search left open for an
 * afternoon still sees what the catalogue says now.
 */
internal class ProjectBrowseSession(private val clock: () -> Long = System::currentTimeMillis) {

    /** One search: what was asked of which catalogue type, in what order, narrowed how, for which pack. */
    data class Key(
        val type: String,
        val query: String,
        val sort: BrowseSort,
        val filters: Set<SearchFilter>,
        val packId: String?,
        val hiding: Boolean,
    )

    /** Where a search had got to. */
    class Snapshot(
        val results: List<ModrinthSearchHit>,
        /** Where the next page starts in the catalogue's order. */
        val offset: Int,
        val endReached: Boolean,
        /** What the search leaves out, fixed when it began. */
        val hidden: Set<String>,
        /** What the target pack was read to hold. */
        val present: Set<String>,
        val firstVisibleIndex: Int = 0,
        val firstVisibleOffset: Int = 0,
        val takenAt: Long,
    ) {
        fun scrolledTo(index: Int, offset: Int) =
            Snapshot(results, this.offset, endReached, hidden, present, index, offset, takenAt)
    }

    private val byKey = object : LinkedHashMap<Key, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Snapshot>?): Boolean = size > MAX_SEARCHES
    }

    /** The search under [key] as it was left, or null when there is none or it has gone stale. */
    @Synchronized
    fun get(key: Key): Snapshot? = byKey[key]?.takeIf { clock() - it.takenAt <= FRESH_MS }

    @Synchronized
    fun put(key: Key, snapshot: Snapshot) {
        byKey[key] = snapshot
    }

    /** Where the list under [key] was scrolled to, kept with it. */
    @Synchronized
    fun scroll(key: Key, index: Int, offset: Int) {
        byKey[key]?.let { byKey[key] = it.scrolledTo(index, offset) }
    }

    /** A clock reading for a new snapshot. */
    fun now(): Long = clock()

    companion object {
        /** How long a search is shown again without asking: the catalogue's search cache life. */
        const val FRESH_MS = 5 * 60_000L

        /** Searches kept at once, the least recently looked at going first. */
        private const val MAX_SEARCHES = 24
    }
}
