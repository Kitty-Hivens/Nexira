package hivens.launcher.sources

import hivens.core.data.PackOrigin

/**
 * The ids of the two sources the launcher still names outside their modules, as their
 * records carry them. The engine's routing of installs, updates, reattach, repair and art
 * reaches every source through [SourceWorks] and names none of them. What still reads
 * these ids is what has not moved into the source modules yet: the mirror's installer in
 * this module, which stamps its records `Mirror`, the content installer's rule for what a
 * pack takes from the player, the catalogue's project installs (`ENGINE_INSTALLS`) and its
 * project and build pages, the pack screens that show what only the mirror's manifest
 * describes (`PackSettingsSheet`, `PackRuntimeSection`, `PackVersionSection`,
 * `ContentTabState`), the brand colours (`BrandColors`) and the parsing of Modrinth links
 * (`ProjectLinks`). The two source modules take their ids from here. The ids go with that
 * work when it moves.
 */
object EngineSources {
    /** The Hivens mirror. */
    val Mirror: PackOrigin = PackOrigin("Mirror")

    /** Modrinth. */
    val Modrinth: PackOrigin = PackOrigin("Modrinth")
}
