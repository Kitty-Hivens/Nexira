package hivens.look.surface.api

import hivens.look.manager.api.LookApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The surface axis's API: what a module compiles against to ask planes by kind ([Surface]) and to
 * answer them ([SurfaceAnswers]) as a provider of that axis.
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object SurfaceApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.look.surface"

    /** The id of the axis [Surface], which a pack, the person's choice and a provider name it by. */
    const val AXIS: String = "surface"

    /**
     * The version of this API and of the axis's contract. A minor step only adds a kind or a field
     * with a default. A major step removes or changes one.
     *
     * A module build stamps its manifest from the `nexira.lookSurfaceApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}

/**
 * The surface axis as the application carries it: compiled in, and read by the look manager whether a
 * document names it or not. It brings the axis [Surface] and nothing else: no keys, no settings, no
 * provider. Which provider answers it is the manager's to pick.
 */
object SurfaceModule : CompiledModule {
    override val id: String = SurfaceApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(SurfaceModule::class.java, SurfaceApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(SurfaceApi.ID to SurfaceApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(SurfaceApi.ID to setOf(SurfaceApi::class.java.name.substringBeforeLast('.')))
    override val requires: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(Surface)

    internal const val NAME = "surface.name"
}
