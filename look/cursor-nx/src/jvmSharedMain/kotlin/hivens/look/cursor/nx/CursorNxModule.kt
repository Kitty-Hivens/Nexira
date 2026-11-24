package hivens.look.cursor.nx

import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorAnswers
import hivens.look.cursor.api.CursorApi
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The nx cursor provider as the application carries it: compiled in, and brought in by the look manager
 * where the person's choice names nx for cursor. It answers the cursor axis ([NxCursorProvider]) and keeps
 * nothing of the person's: nx's pointers have no setting.
 */
object CursorNxModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.cursor.nx"

    /** The family it answers the cursor axis as, which a pack names it by. */
    const val FAMILY: String = "nx"

    override val id: String = ID
    override val name: String = "cursor.nx.name"
    override val strings: ModuleStrings = ModuleStrings.resources(CursorNxModule::class.java, ID)

    // The system's pointers by their module's id: nx is those, and has none without them.
    override val requires: Map<String, ApiVersion> =
        mapOf(LookApi.ID to LookApi.VERSION, CursorApi.ID to CursorApi.VERSION, "nexira.look.cursor.system" to ApiVersion(1, 0))
    override val contributions: List<ModuleContribution> = listOf(NxCursorProvider)
}

/** The nx family of the cursor axis: the system's pointers. nx has none it would answer otherwise. */
internal object NxCursorProvider : LookProvider<CursorAnswers> {
    override val axis: String = Cursor.id
    override val family: String = CursorNxModule.FAMILY
    override val contract = CursorApi.VERSION
    override val label: String = "cursor.nx.provider"
    override val basedOn: String = "system"

    override fun open(context: LookContext): LookAnswers<CursorAnswers> = LookAnswers.over(context, Cursor) { system -> system }
}
