package hivens.look.cursor.system

import androidx.compose.ui.input.pointer.PointerIcon
import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorAnswers
import hivens.look.cursor.api.CursorApi
import hivens.look.cursor.api.CursorRole
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The system cursor provider as the application carries it: compiled in, and brought in by the look manager
 * where the person's choice names system for cursor, or a provider that stands on it. It answers the cursor
 * axis ([SystemCursorProvider]) and keeps nothing of the person's: the system's pointers are set in the
 * system.
 */
object CursorSystemModule : CompiledModule {
    /** What a document names this module by, and what a module that stands on it requires. */
    const val ID: String = "nexira.look.cursor.system"

    /** The family it answers the cursor axis as, which a pack names it by. */
    const val FAMILY: String = "system"

    override val id: String = ID
    override val name: String = "cursor.system.name"
    override val strings: ModuleStrings = ModuleStrings.resources(CursorSystemModule::class.java, ID)
    override val version: ApiVersion = ApiVersion(1, 0)
    override val requires: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION, CursorApi.ID to CursorApi.VERSION)
    override val contributions: List<ModuleContribution> = listOf(SystemCursorProvider)
}

/**
 * The system family of the cursor axis. What is pressed is under the arrow, as in every native interface.
 * The rest is what the system has for it, and nothing where it has none, so no pointer is swapped for one
 * the person's system would not show. The pointers are looked up as they are first drawn, when the toolkit
 * is up, which it is not yet when the boot window asks for its look.
 */
internal object SystemCursorProvider : LookProvider<CursorAnswers> {
    override val axis: String = Cursor.id
    override val family: String = CursorSystemModule.FAMILY
    override val contract = CursorApi.VERSION
    override val label: String = "cursor.system.provider"

    override fun open(context: LookContext): LookAnswers<CursorAnswers> = LookAnswers.of(ANSWERS)

    val ANSWERS: CursorAnswers = CursorAnswers.build { pointer = { role -> held.pointer(role) } }

    private val held: SystemPointers by lazy { systemPointers() }
}

/** What the system has for the roles of the cursor axis. */
internal interface SystemPointers {
    /** The system's pointer for [role], the same instance on every ask, null where it has none. */
    fun pointer(role: CursorRole): PointerIcon?
}

/** The pointers of the system the launcher runs on. */
internal expect fun systemPointers(): SystemPointers
