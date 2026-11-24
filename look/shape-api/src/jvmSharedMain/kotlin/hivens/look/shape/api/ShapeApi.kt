package hivens.look.shape.api

import hivens.look.manager.api.LookApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The shape axis's API: what a module compiles against to ask corners and outlines by role ([Shape])
 * and to answer them ([ShapeAnswers]) as a provider of that axis.
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object ShapeApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.look.shape"

    /** The id of the axis [Shape], which a pack, the person's choice and a provider name it by. */
    const val AXIS: String = "shape"

    /**
     * The version of this API and of the axis's contract. A minor step only adds a role. A major step
     * removes or changes one.
     *
     * A module build stamps its manifest from the `nexira.lookShapeApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}

/**
 * The shape axis as the application carries it: compiled in, and read by the look manager whether a
 * document names it or not. It brings the axis [Shape] and nothing else: no keys, no settings, no
 * provider. Which provider answers it is the manager's to pick.
 */
object ShapeModule : CompiledModule {
    override val id: String = ShapeApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(ShapeModule::class.java, ShapeApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(ShapeApi.ID to ShapeApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(ShapeApi.ID to setOf(ShapeApi::class.java.name.substringBeforeLast('.')))
    override val requires: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(Shape)

    internal const val NAME = "shape.name"
}
