package hivens.look.feedback.api

import hivens.look.manager.api.LookApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The feedback axis's API: what a module compiles against to ask how a widget answers an interaction
 * ([Feedback]) and to answer it ([FeedbackAnswers]) as a provider of that axis.
 *
 * A module built against it names [ID] and the version it was built against in its manifest, and the
 * loader refuses it where this launcher carries a version that does not serve that one.
 */
object FeedbackApi {
    /** What a manifest names this API by. */
    const val ID: String = "nexira.look.feedback"

    /** The id of the axis [Feedback], which a pack, the person's choice and a provider name it by. */
    const val AXIS: String = "feedback"

    /**
     * The version of this API and of the axis's contract. A minor step only adds a role or a target. A
     * major step removes or changes one.
     *
     * A module build stamps its manifest from the `nexira.lookFeedbackApi` Gradle property, which a test
     * holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}

/**
 * The feedback axis as the application carries it: compiled in, and read by the look manager whether a
 * document names it or not. It brings the axis [Feedback] and nothing else: no keys, no settings, no
 * provider. Which provider answers it is the manager's to pick.
 */
object FeedbackModule : CompiledModule {
    override val id: String = FeedbackApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(FeedbackModule::class.java, FeedbackApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(FeedbackApi.ID to FeedbackApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(FeedbackApi.ID to setOf(FeedbackApi::class.java.name.substringBeforeLast('.')))
    override val requires: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(Feedback)

    internal const val NAME = "feedback.name"
}
