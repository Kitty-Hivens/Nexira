package hivens.core.api.interfaces

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Read side of one pack source: search the catalogue, fetch a pack's detail
 * page, and list its installable versions. One implementation per [origin]
 * (mirror, Modrinth, ...); the Browse UI selects by origin and stays free of
 * source-specific wire shapes. The write side is the installer (Phase 1-D).
 */
interface IPackCatalogueService {
    val origin: PackOrigin

    /**
     * Catalogue search. [page] is zero-based. Sources without a query endpoint
     * (the mirror) filter their full listing client-side; [query] blank lists
     * everything.
     */
    suspend fun search(query: String, page: Int = 0): List<CataloguePack>

    /**
     * The same search as a stale-then-fresh stream: a cached answer arrives at
     * once and the reloaded one replaces it.
     *
     * A screen that calls [search] once shows whatever the cache held and never
     * sees the refresh that read set off, so the catalogue it paints goes on
     * being the one from the last visit until something clears it. The default
     * is the single answer, for a source that does not cache.
     */
    fun searchStream(query: String, page: Int = 0): Flow<List<CataloguePack>> = flow { emit(search(query, page)) }

    /** Full detail for one pack (hero, body, gallery, versions). */
    suspend fun details(packId: String): CataloguePackDetails

    /** Installable versions, newest first where the source orders them. */
    suspend fun versions(packId: String): List<CataloguePackVersion>
}
