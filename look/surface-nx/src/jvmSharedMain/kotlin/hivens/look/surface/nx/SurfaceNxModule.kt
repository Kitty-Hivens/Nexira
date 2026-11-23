package hivens.look.surface.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.surface.api.Surface
import hivens.look.surface.api.SurfaceAnswers
import hivens.look.surface.api.SurfaceApi
import hivens.ui.theme.nxSurfaceAnswers
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings

/**
 * The nx surface provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for surface. It answers the surface axis
 * ([NxSurfaceProvider]) and keeps nothing of the person's: nx's planes have no setting.
 */
object SurfaceNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.surface.nx"

    /** The family it answers the surface axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "surface.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(SurfaceNxModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(NxSurfaceProvider)
}

/**
 * The nx family of the surface axis: nx's planes, as nx-ui answers them ([nxSurfaceAnswers]). They
 * depend on nothing the person chose, so the window drawn before the host is up gets the same
 * answers as the shell, and they never change while open.
 */
internal object NxSurfaceProvider : LookProvider<SurfaceAnswers> {
    override val axis: String = Surface.id
    override val family: String = SurfaceNxModule.FAMILY
    override val contract = SurfaceApi.VERSION
    override val label: String = "surface.nx.provider"

    override fun open(context: LookContext): LookAnswers<SurfaceAnswers> = LookAnswers.of(nxSurfaceAnswers())
}
