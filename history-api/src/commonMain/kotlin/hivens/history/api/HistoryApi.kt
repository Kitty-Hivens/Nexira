package hivens.history.api

import hivens.widget.model.ApiVersion

/**
 * The history API: what another module compiles against to read where the pages shown have been and to step back
 * and forward through them, in [HistoryKeys].
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the loader refuses
 * it where this launcher carries a version that does not serve that one (see [ApiVersion.serves]).
 */
object HistoryApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.history"

    /** The version of this API. A minor step only adds, a major step removes or changes a key or a value type. */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}
