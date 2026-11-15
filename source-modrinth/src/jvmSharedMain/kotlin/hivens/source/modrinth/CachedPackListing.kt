package hivens.source.modrinth

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackListing
import hivens.core.cache.Cache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A pack listing that answers a repeated search from cache and refreshes behind it.
 *
 * Browse is a screen people leave and come back to, and its source switcher is
 * two clicks apart: every one of those was a fresh round trip, and the screen
 * had nothing to show while it ran but a spinner over the list it had just been
 * showing. Reading through the cache makes the answer to a question already
 * asked immediate, and [searchStream] hands the screen the stale answer and the
 * fresh one in turn so the refresh replaces the list instead of clearing it.
 *
 * Wraps rather than being folded into the listing: a listing's job is to map its
 * own wire shape onto [CatalogueCard], and a source that pages wants the same
 * caching over the result of that, whichever source it is. Nothing in it is
 * Modrinth's, it lives here because Modrinth is the one source that uses it.
 */
class CachedPackListing(
    private val delegate: PackListing,
    /** The source's id, since one cache may be shared by several sources. */
    private val source: String,
    private val cache: Cache<List<CatalogueCard>>,
) : PackListing by delegate {

    override suspend fun search(query: String, page: Int): List<CatalogueCard> =
        cache.get(key(query, page)) { delegate.search(query, page) }

    override fun searchStream(query: String, page: Int): Flow<List<CatalogueCard>> =
        cache.flow(key(query, page)) { delegate.search(query, page) }.map { it.value }

    /**
     * Two sources answering the same words are two different answers, so the source is
     * part of the key. Trimmed and folded to lower case because the difference between
     * "Create" and "create " is a keystroke, not a query.
     */
    private fun key(query: String, page: Int): String =
        "$source|$page|${query.trim().lowercase()}"
}
