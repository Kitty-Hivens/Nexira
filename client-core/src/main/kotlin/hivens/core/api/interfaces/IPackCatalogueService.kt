package hivens.core.api.interfaces

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackOrigin

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

    /** Full detail for one pack (hero, body, gallery, versions). */
    suspend fun details(packId: String): CataloguePackDetails

    /** Installable versions, newest first where the source orders them. */
    suspend fun versions(packId: String): List<CataloguePackVersion>
}
