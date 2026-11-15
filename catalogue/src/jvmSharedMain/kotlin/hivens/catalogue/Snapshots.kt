package hivens.catalogue

import hivens.catalogue.api.BrowseSort
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.ProjectHit
import hivens.catalogue.api.ScrollMark
import hivens.catalogue.api.SearchFilter
import hivens.core.api.catalogue.CataloguePackDetails

/**
 * What a pack listing was last showing for a source and a query, kept for as long as
 * the app runs.
 *
 * The catalogue cache underneath answers a repeated search without the network, but it
 * answers from a coroutine, and a list that has to await an answer draws something in
 * the meantime. Drawing a spinner over a list the person was reading a second ago is
 * the part that shows. This holds the list itself, so the reader paints it at once and
 * the refresh replaces it silently.
 *
 * It also holds how far the list was paged and where the person had got to in it,
 * which the cache cannot. Nothing here expires, because nothing here decides anything:
 * it is the last thing shown, shown again only while the real answer is on its way.
 */
class PackSnapshots {

    /** One source and query's list as it stood, with the paging that produced it and the place in it. */
    data class Snapshot(
        val packs: List<CatalogueCard>,
        val nextPage: Int,
        val endReached: Boolean,
        val scroll: ScrollMark = ScrollMark(),
    )

    private val byKey = LinkedHashMap<String, Snapshot>()

    /** The last page read for a pack, so returning to it paints what was there. */
    private val detailsByKey = LinkedHashMap<String, CataloguePackDetails>()

    @Synchronized
    fun details(origin: String, packId: String): CataloguePackDetails? = detailsByKey["$origin|$packId"]

    @Synchronized
    fun putDetails(origin: String, packId: String, details: CataloguePackDetails) {
        val k = "$origin|$packId"
        detailsByKey.remove(k)
        detailsByKey[k] = details
        while (detailsByKey.size > MAX_KEYS) detailsByKey.remove(detailsByKey.keys.first())
    }

    @Synchronized
    fun get(origin: String, query: String): Snapshot? = byKey[key(origin, query)]

    /**
     * Remembers a list, or forgets what was remembered when there is no list.
     *
     * Nothing is not a thing to restore, and storing it was self-reinforcing: an empty
     * snapshot read back painted a blank pane with neither the empty message nor its
     * retry, and one source briefly unreachable left Browse blank for the life of the
     * process.
     */
    @Synchronized
    fun put(origin: String, query: String, snapshot: Snapshot) {
        val k = key(origin, query)
        byKey.remove(k)
        if (snapshot.packs.isEmpty()) return
        byKey[k] = snapshot
        // Every query typed on the way to the intended one leaves a snapshot, so the
        // map is bounded by the oldest going out rather than by nothing.
        while (byKey.size > MAX_KEYS) byKey.remove(byKey.keys.first())
    }

    private fun key(origin: String, query: String) = "$origin|${query.trim().lowercase()}"

    private companion object {
        const val MAX_KEYS = 32
    }
}

/**
 * What project searches have shown, kept for the life of the process, so a return to a
 * search finds its pages, what the pack was read to hold and the place in the list as
 * they were left.
 *
 * A snapshot is taken as current for [FRESH_MS], the time the catalogue's search pages
 * are cached for, and asked again after that, so a search left open for an afternoon
 * still sees what the catalogue says now.
 */
class ProjectSnapshots(private val clock: () -> Long = System::currentTimeMillis) {

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
        val results: List<ProjectHit>,
        /** Where the next page starts in the catalogue's order. */
        val offset: Int,
        val endReached: Boolean,
        /** What the search leaves out, fixed when it began. */
        val hidden: Set<String>,
        /** What the target pack was read to hold. */
        val present: Set<String>,
        /** What the pack's content folders looked like when [present] was read. */
        val presentStamp: String? = null,
        val scroll: ScrollMark = ScrollMark(),
        val takenAt: Long,
    ) {
        fun scrolledTo(mark: ScrollMark) = Snapshot(results, offset, endReached, hidden, present, presentStamp, mark, takenAt)
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
    fun scroll(key: Key, mark: ScrollMark) {
        byKey[key]?.let { byKey[key] = it.scrolledTo(mark) }
    }

    /** A clock reading for a new snapshot. */
    fun now(): Long = clock()

    companion object {
        /** How long a search is shown again without asking: the catalogue's search cache life. */
        const val FRESH_MS: Long = 5 * 60_000L

        /** Searches kept at once, the least recently looked at going first. */
        private const val MAX_SEARCHES = 24
    }
}
