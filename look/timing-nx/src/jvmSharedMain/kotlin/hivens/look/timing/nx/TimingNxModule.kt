package hivens.look.timing.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.timing.api.Timing
import hivens.look.timing.api.TimingAnswers
import hivens.look.timing.api.TimingApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The nx timing provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for timing. It answers the timing axis
 * ([NxTimingProvider]) and keeps nothing of the person's: nx's waits have no setting.
 */
object TimingNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.timing.nx"

    /** The family it answers the timing axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "timing.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(TimingNxModule::class.java, ID)
    override val requires: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION, TimingApi.ID to TimingApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(NxTimingProvider)
}

/**
 * The nx family of the timing axis: nx's waits ([nxTimingAnswers]). They
 * depend on nothing the person chose, so the window drawn before the host is up gets the same
 * answers as the shell, and they never change while open.
 */
internal object NxTimingProvider : LookProvider<TimingAnswers> {
    override val axis: String = Timing.id
    override val family: String = TimingNxModule.FAMILY
    override val contract = TimingApi.VERSION
    override val label: String = "timing.nx.provider"

    override fun open(context: LookContext): LookAnswers<TimingAnswers> = LookAnswers.of(nxTimingAnswers())
}
