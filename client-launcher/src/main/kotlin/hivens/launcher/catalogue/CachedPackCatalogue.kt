package hivens.launcher.catalogue

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.cache.Cache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A catalogue that answers a repeated search from cache and refreshes behind it.
 *
 * Browse is a screen people leave and come back to, and its source switcher is
 * two clicks apart -- every one of those was a fresh round trip, and the screen
 * had nothing to show while it ran but a spinner over the list it had just been
 * showing. Reading through the cache makes the answer to a question already
 * asked immediate, and [searchStream] hands the screen the stale answer and the
 * fresh one in turn so the refresh replaces the list instead of clearing it.
 *
 * Wraps rather than being folded into each source: a catalogue's job is to map
 * its own wire shape onto [CataloguePack], and both sources want the same
 * caching over the result of that.
 */
class CachedPackCatalogue(
    private val delegate: IPackCatalogueService,
    private val cache: Cache<List<CataloguePack>>,
) : IPackCatalogueService by delegate {

    override suspend fun search(query: String, page: Int): List<CataloguePack> =
        cache.get(key(query, page)) { delegate.search(query, page) }

    override fun searchStream(query: String, page: Int): Flow<List<CataloguePack>> =
        cache.flow(key(query, page)) { delegate.search(query, page) }.map { it.value }

    /**
     * One cache is shared by every source, so the origin is part of the key --
     * two catalogues answering the same words are two different answers.
     * Trimmed and folded to lower case because the difference between "Create"
     * and "create " is a keystroke, not a query.
     */
    private fun key(query: String, page: Int): String =
        "${delegate.origin}|$page|${query.trim().lowercase()}"
}
