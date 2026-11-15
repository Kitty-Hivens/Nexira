package hivens.catalogue.api

import hivens.widget.model.ApiVersion

/**
 * The catalogue module's API: what another module compiles against to read and fire
 * the keys in [CatalogueKeys].
 *
 * A module built against it names [ID] and the version it was built against in its
 * manifest, and the loader refuses it where this launcher carries a version that
 * does not serve that one (see [ApiVersion.serves]).
 */
object CatalogueApi {
    /** What a manifest names this API by, and what a document names the module by to bring it in. */
    const val ID: String = "nexira.catalogue"

    /**
     * The version of this API. A minor step only adds. A major step removes or
     * changes a key or a value type, which also covers a new case of a sealed value
     * or an enum, since a module that matched on the old ones has no branch for it.
     *
     * The module builds stamp their manifests from the `nexira.catalogueApi` Gradle
     * property, which a test holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}
