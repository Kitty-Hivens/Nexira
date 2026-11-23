package hivens.look.timing.api

import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The timing axis's API: what a module compiles against to ask how long a transient piece of the
 * interface waits ([Timing]) and to answer it ([TimingAnswers]) as a provider of that axis.
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object TimingApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.look.timing"

    /** The id of the axis [Timing], which a pack, the person's choice and a provider name it by. */
    const val AXIS: String = "timing"

    /**
     * The version of this API and of the axis's contract. A minor step only adds a role or a kind. A
     * major step removes or changes one.
     *
     * A module build stamps its manifest from the `nexira.lookTimingApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}

/**
 * The timing axis as the application carries it: compiled in, and read by the look manager whether a
 * document names it or not. It brings the axis [Timing] and nothing else: no keys, no settings, no
 * provider. Which provider answers it is the manager's to pick.
 */
object TimingModule : CompiledModule {
    override val id: String = TimingApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(TimingModule::class.java, TimingApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(TimingApi.ID to TimingApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(TimingApi.ID to setOf(TimingApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(Timing)

    internal const val NAME = "timing.name"
}
