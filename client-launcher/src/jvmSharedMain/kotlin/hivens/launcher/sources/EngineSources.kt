package hivens.launcher.sources

import hivens.core.data.PackOrigin

/**
 * The two sources whose installs, updates, reattach and content updates the engine still
 * runs itself, by the ids their records carry. What they list, a pack's page and its
 * builds are their source modules' own. These ids go with that work when it moves into
 * the modules, and nothing that lists, searches or shows a source reads them.
 */
object EngineSources {
    /** The Hivens mirror. */
    val Mirror: PackOrigin = PackOrigin("Mirror")

    /** Modrinth. */
    val Modrinth: PackOrigin = PackOrigin("Modrinth")
}
