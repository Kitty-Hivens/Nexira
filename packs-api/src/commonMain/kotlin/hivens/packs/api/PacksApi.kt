package hivens.packs.api

import hivens.widget.model.ApiVersion

/**
 * The packs module's API: what another module compiles against to read and fire the
 * keys in [PackKeys].
 *
 * A module built against it names [ID] and the version it was built against in its
 * manifest, and the loader refuses it where this launcher carries a version that
 * does not serve that one (see [ApiVersion.serves]).
 */
object PacksApi {
    /** What a manifest names this API by, and what a document names the module by to bring it in. */
    const val ID: String = "nexira.packs"

    /**
     * The version of this API. A minor step only adds. A major step removes or
     * changes a key or a value type, which also covers a new case of a sealed value,
     * since a module that matched on the old ones has no branch for it.
     *
     * The module builds stamp their manifests from the `nexira.packsApi` Gradle
     * property, which a test holds equal to this.
     *
     * 2.0: [PackSource.origin], [InstallView.origin] and [InstallRequest.origin] carry the
     * source's id exactly as a pack's record carries it (`Mirror`, `Local`), where 1.0 gave
     * the source's name in lower case (`mirror`). An install request is matched by that id
     * exactly, case included.
     *
     * 3.0: [PackPages.Pack] is a value of the pages API (`hivens.pages.api.PageValue`), which left
     * the widget kernel.
     */
    val VERSION: ApiVersion = ApiVersion(3, 0)
}
