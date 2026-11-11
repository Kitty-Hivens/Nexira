package hivens.launcher.catalogue

import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.data.PackOrigin

/**
 * Indexes the wired [IPackCatalogueService]s by [PackOrigin] so the Browse UI
 * can pick a source by origin without knowing the concrete providers.
 */
class PackCatalogueRegistry(catalogues: List<IPackCatalogueService>) {
    private val byOrigin: Map<PackOrigin, IPackCatalogueService> = catalogues.associateBy { it.origin }

    /** Browsable origins in registration order (drives the Browse source tabs). */
    val origins: List<PackOrigin> = catalogues.map { it.origin }

    fun forOrigin(origin: PackOrigin): IPackCatalogueService? = byOrigin[origin]
}
