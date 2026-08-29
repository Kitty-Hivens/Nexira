package hivens.ui.screens.browse

import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin

/**
 * What Browse was last showing for a source and a query, kept for as long as the
 * app runs.
 *
 * The catalogue cache underneath answers a repeated search without the network,
 * but it answers it from a coroutine, and a screen that has to await an answer
 * has to draw something in the meantime. Drawing a spinner over a list the user
 * was reading a second ago -- because they flipped to the other source and back
 * -- is the part that shows. This holds the list itself, so the screen paints it
 * on the first frame and lets the refresh replace it silently.
 *
 * It also holds how far the list was paged, which the cache cannot: the pages a
 * player scrolled through were assembled here, one request at a time, and coming
 * back to twenty results after scrolling to two hundred is its own kind of loss.
 *
 * Deliberately not a cache: nothing here expires, because nothing here is used to
 * decide anything. It is the last thing shown, and it is only ever shown again
 * while the real answer is on its way.
 */
class BrowseSession {

    /**
     * One source+query's list as it stood, with the paging that produced it and
     * the place in it the reader had got to.
     *
     * The position is here because the list and the position are the same
     * memory: restoring eighty results and dropping the reader at the top of them
     * is most of the loss the restore exists to avoid, and a scroll state shared
     * across queries is worse still -- it lands them wherever the previous list
     * had been, which for a shorter one is the end.
     */
    data class Snapshot(
        val packs: List<CataloguePack>,
        val nextPage: Int,
        val endReached: Boolean,
        val firstVisibleIndex: Int = 0,
        val firstVisibleOffset: Int = 0,
    )

    /**
     * The source that was being browsed. Kept beside the lists because it is the
     * same question one level up: opening a pack and coming back put the switcher
     * on the first registered source rather than the one the pack came from, so a
     * look at anything outside the mirror was undone by looking at it.
     */
    var origin: PackOrigin? = null

    private val byKey = LinkedHashMap<String, Snapshot>()

    fun get(origin: PackOrigin, query: String): Snapshot? = byKey[key(origin, query)]

    /**
     * Remembers a list, or forgets what was remembered when there is no list.
     *
     * Nothing is not a thing to restore, and storing it was self-reinforcing. An
     * empty snapshot read back became a Loaded list, which paints a blank pane with
     * neither the empty message nor its retry; the screen then treated every later
     * failure as one it already had an answer for and stopped reporting them, paging
     * was off because the snapshot said the end was reached, and the way out
     * rewrote the same emptiness. One source briefly unreachable therefore left
     * Browse blank and inert for the life of the process. The cache underneath
     * states the same rule as `shouldStore`; this is the layer above it.
     */
    fun put(origin: PackOrigin, query: String, snapshot: Snapshot) {
        val k = key(origin, query)
        byKey.remove(k)
        if (snapshot.packs.isEmpty()) return
        byKey[k] = snapshot
        // Every query typed on the way to the intended one leaves a snapshot, so
        // the map is bounded by the oldest going out rather than by nothing.
        while (byKey.size > MAX_KEYS) byKey.remove(byKey.keys.first())
    }

    private fun key(origin: PackOrigin, query: String) = "$origin|${query.trim().lowercase()}"

    private companion object {
        const val MAX_KEYS = 32
    }
}
