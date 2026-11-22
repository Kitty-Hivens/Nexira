package hivens.look.stroke.api

import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The stroke axis's API: what a module compiles against to ask strokes by role ([Stroke]) and to answer
 * them ([StrokeAnswers]) as a provider of that axis.
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object StrokeApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.look.stroke"

    /** The id of the axis [Stroke], which a pack, the person's choice and a provider name it by. */
    const val AXIS: String = "stroke"

    /**
     * The version of this API and of the axis's contract. A minor step only adds a role. A major step
     * removes or changes one, or changes what a role is asked with.
     *
     * A module build stamps its manifest from the `nexira.lookStrokeApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}

/**
 * The stroke axis as the application carries it: compiled in, and read by the look manager whether a
 * document names it or not. It brings the axis [Stroke] and nothing else: no keys, no settings, no
 * provider. Which provider answers it is the manager's to pick.
 */
object StrokeModule : CompiledModule {
    override val id: String = StrokeApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(StrokeModule::class.java, StrokeApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(StrokeApi.ID to StrokeApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(StrokeApi.ID to setOf(StrokeApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(Stroke)

    internal const val NAME = "stroke.name"
}
