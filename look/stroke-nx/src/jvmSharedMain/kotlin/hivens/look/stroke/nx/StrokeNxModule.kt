package hivens.look.stroke.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.StrokeAnswers
import hivens.look.stroke.api.StrokeApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The nx stroke provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for stroke. It answers the stroke axis
 * ([NxStrokeProvider]) and keeps nothing of the person's: nx's strokes have no setting.
 */
object StrokeNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.stroke.nx"

    /** The family it answers the stroke axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "stroke.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(StrokeNxModule::class.java, ID)
    override val requires: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION, StrokeApi.ID to StrokeApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(NxStrokeProvider)
}

/**
 * The nx family of the stroke axis: nx's strokes ([nxStrokeAnswers]). They
 * depend on nothing the person chose, so the window drawn before the host is up gets the same
 * answers as the shell, and they never change while open.
 */
internal object NxStrokeProvider : LookProvider<StrokeAnswers> {
    override val axis: String = Stroke.id
    override val family: String = StrokeNxModule.FAMILY
    override val contract = StrokeApi.VERSION
    override val label: String = "stroke.nx.provider"

    override fun open(context: LookContext): LookAnswers<StrokeAnswers> = LookAnswers.of(nxStrokeAnswers())
}
