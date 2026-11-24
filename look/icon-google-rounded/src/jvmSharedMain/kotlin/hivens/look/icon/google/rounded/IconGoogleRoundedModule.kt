package hivens.look.icon.google.rounded

import hivens.look.icon.api.Icon
import hivens.look.icon.api.IconAnswers
import hivens.look.icon.api.IconApi
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings

/**
 * The Google icon provider in the Rounded style as the application carries it: compiled in, and brought
 * in by the look manager where the person's choice names google-rounded for icon. It answers the icon
 * axis ([GoogleRoundedProvider]) and keeps nothing of the person's.
 */
object IconGoogleRoundedModule : CompiledModule {
    /** What a document names this module by. */
    const val ID: String = "nexira.look.icon.google.rounded"

    /** The family it answers the icon axis as, which a pack names it by. */
    const val FAMILY: String = "google-rounded"

    override val id: String = ID
    override val name: String = "icon.google.rounded.name"
    override val strings: ModuleStrings = ModuleStrings.resources(IconGoogleRoundedModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(GoogleRoundedProvider)
}

/**
 * The google-rounded family of the icon axis: Material Symbols Rounded. The font and its names are read
 * as it is opened, off the thread that draws, so no icon waits for them.
 */
internal object GoogleRoundedProvider : LookProvider<IconAnswers> {
    override val axis: String = Icon.id
    override val family: String = IconGoogleRoundedModule.FAMILY
    override val contract = IconApi.VERSION
    override val label: String = "icon.google.rounded.provider"

    override fun open(context: LookContext): LookAnswers<IconAnswers> {
        SymbolFont.load()
        return LookAnswers.of(googleRoundedIcons())
    }
}

/** Material Symbols Rounded as the icon axis's answers. Made once, so an icon is the same value wherever it is read. */
fun googleRoundedIcons(): IconAnswers = ANSWERS

private val ANSWERS = IconAnswers.build { library = SymbolFont::drawing }
