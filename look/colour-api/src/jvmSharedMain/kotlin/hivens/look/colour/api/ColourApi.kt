package hivens.look.colour.api

import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The colour axis's API: what a module compiles against to ask colour by role ([Ink], [Paint], [Fill],
 * [Line], [Ground], [Media]) and to answer it ([ColourAnswers]) as a provider of the axis [Colour].
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object ColourApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.look.colour"

    /** The id of the axis [Colour], which a pack, the person's choice and a provider name it by. */
    const val AXIS: String = "colour"

    /**
     * The version of this API and of the axis's contract. A minor step only adds a role. A major step
     * removes or changes one.
     *
     * A module build stamps its manifest from the `nexira.lookColourApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}

/**
 * The colour axis as the application carries it: compiled in, and read by the look manager whether a
 * document names it or not. It brings the axis [Colour] and nothing else: no keys, no settings, no
 * provider. Which provider answers it is the manager's to pick.
 */
object ColourModule : CompiledModule {
    override val id: String = ColourApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(ColourModule::class.java, ColourApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(ColourApi.ID to ColourApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(ColourApi.ID to setOf(ColourApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(Colour)

    internal const val NAME = "colour.name"
}
