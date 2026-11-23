package hivens.look.type.nx

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.type.api.Type
import hivens.look.type.api.TypeAnswers
import hivens.look.type.api.TypeApi
import hivens.ui.theme.nxTypeAnswers
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings

/**
 * The nx type provider as the application carries it: compiled in, and brought in by the look
 * manager where the person's choice names nx for type. It answers the type axis
 * ([NxTypeProvider]) and keeps nothing of the person's: nx's faces have no setting.
 */
object TypeNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.type.nx"

    /** The family it answers the type axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "type.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(TypeNxModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(NxTypeProvider)
}

/**
 * The nx family of the type axis: nx's faces, as nx-ui answers them ([nxTypeAnswers]). They
 * depend on nothing the person chose, so the window drawn before the host is up gets the same
 * answers as the shell, and they never change while open.
 */
internal object NxTypeProvider : LookProvider<TypeAnswers> {
    override val axis: String = Type.id
    override val family: String = TypeNxModule.FAMILY
    override val contract = TypeApi.VERSION
    override val label: String = "type.nx.provider"

    override fun open(context: LookContext): LookAnswers<TypeAnswers> = LookAnswers.of(nxTypeAnswers())
}
