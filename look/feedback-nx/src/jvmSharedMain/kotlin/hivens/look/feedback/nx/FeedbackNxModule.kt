package hivens.look.feedback.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackAnswers
import hivens.look.feedback.api.FeedbackApi
import hivens.ui.theme.nxFeedbackAnswers
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings

/**
 * The nx feedback provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for feedback. It answers the feedback axis
 * ([NxFeedbackProvider]) and keeps nothing of the person's: nx's feedback has no setting.
 */
object FeedbackNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.feedback.nx"

    /** The family it answers the feedback axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "feedback.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(FeedbackNxModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(NxFeedbackProvider)
}

/**
 * The nx family of the feedback axis: nx's state layers, as nx-ui answers them ([nxFeedbackAnswers]). They
 * depend on nothing the person chose, so the window drawn before the host is up gets the same
 * answers as the shell, and they never change while open.
 */
internal object NxFeedbackProvider : LookProvider<FeedbackAnswers> {
    override val axis: String = Feedback.id
    override val family: String = FeedbackNxModule.FAMILY
    override val contract = FeedbackApi.VERSION
    override val label: String = "feedback.nx.provider"

    override fun open(context: LookContext): LookAnswers<FeedbackAnswers> = LookAnswers.of(nxFeedbackAnswers())
}
