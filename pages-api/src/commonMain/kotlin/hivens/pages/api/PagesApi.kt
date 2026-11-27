package hivens.pages.api

import hivens.widget.model.ApiVersion

/**
 * The pages API: what another module compiles against to open a page and read what it was opened with, in
 * [PagesKeys], [PageKeys] and [PageValue].
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the loader refuses
 * it where this launcher carries a version that does not serve that one (see [ApiVersion.serves]).
 */
object PagesApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.pages"

    /** The version of this API. A minor step only adds, a major step removes or changes a key or a value type. */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}
