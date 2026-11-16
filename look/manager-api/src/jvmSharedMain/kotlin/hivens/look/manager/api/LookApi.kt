package hivens.look.manager.api

import hivens.widget.model.ApiVersion

/**
 * The look manager's API: what a module compiles against to bring an axis of the look ([LookAxis]),
 * a provider that answers one ([LookProvider]) or a pack naming a provider per axis ([LookPack]), and
 * what the host and the widgets read the look through ([LookKeys]).
 *
 * The manager knows no axis. It takes the axes, providers and packs the modules bring, picks one
 * provider per axis from the person's choice ([LookChoice]), loads only those, and hands their answers
 * to the composition. A widget that wants to respect the person's choice reads an axis's answers where
 * its contract puts them, and one that does not care draws itself.
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object LookApi {
    /** What a manifest names this API by, and what a document names the manager by to bring it in. */
    const val ID: String = "nexira.look.manager"

    /**
     * The version of this API. A minor step only adds. A major step removes or changes a type.
     *
     * A module build stamps its manifest from the `nexira.lookManagerApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)

    /**
     * The pack an axis falls back on where the chosen pack names no provider for it. Only a module
     * compiled into the launcher may bring a pack of this id.
     */
    const val DEFAULT_PACK: String = "nexira"
}
