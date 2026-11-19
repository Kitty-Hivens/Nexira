package hivens.look.shape.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.shape.api.Shape
import hivens.look.shape.api.ShapeAnswers
import hivens.look.shape.api.ShapeApi
import hivens.ui.theme.nxShapeAnswers
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings

/**
 * The nx shape provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for shape. It answers the shape axis
 * ([NxShapeProvider]) and keeps nothing of the person's: nx's corners have no setting.
 */
object ShapeNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.shape.nx"

    /** The family it answers the shape axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "shape.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(ShapeNxModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(NxShapeProvider)
}

/**
 * The nx family of the shape axis: nx's corners, as nx-ui answers them ([nxShapeAnswers]). They
 * depend on nothing the person chose, so the window drawn before the host is up gets the same
 * answers as the shell, and they never change while open.
 */
internal object NxShapeProvider : LookProvider<ShapeAnswers> {
    override val axis: String = Shape.id
    override val family: String = ShapeNxModule.FAMILY
    override val contract = ShapeApi.VERSION
    override val label: String = "shape.nx.provider"

    override fun open(context: LookContext): LookAnswers<ShapeAnswers> = LookAnswers.of(nxShapeAnswers())
}
