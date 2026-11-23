package hivens.look.packs

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookPack
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings

/**
 * The packs that ship, as the application carries them: compiled in, and read by the look manager
 * whether a document names this module or not. Only a module compiled in may bring the default pack,
 * which is why it lives here and in no jar.
 */
object LookPacksModule : CompiledModule {
    const val ID: String = "nexira.look.packs"

    override val id: String = ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(LookPacksModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(NexiraPack)

    private const val NAME = "packs.name"
}

/**
 * The default pack: Nexira's own look, the families every axis takes where the person chose no pack or
 * their pack names none for it. It names an axis only once that axis exists, so an axis added later
 * draws its base answers until its family is named here.
 */
object NexiraPack : LookPack {
    override val id: String = LookApi.DEFAULT_PACK
    override val label: String = "packs.nexira"

    // By id, as a pack knows no axis and no provider: every axis that exists, each answered by the nx family.
    override val providers: Map<String, String> =
        listOf("colour", "motion", "shape", "stroke", "surface", "timing").associateWith { "nx" }
}
