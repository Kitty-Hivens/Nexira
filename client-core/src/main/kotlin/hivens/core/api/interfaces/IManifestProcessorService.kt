package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod

interface IManifestProcessorService {
    /** Flattens the tree manifest into a `path -> data` map. */
    fun flattenManifest(manifest: FileManifest): Map<String, FileData>

    fun getOptionalModsForClient(profile: ServerProfile): List<OptionalMod>

    /**
     * Set of jar names to exclude from sync. [userState] maps `mod.id ->
     * enabled?`; mods absent from the map fall back to [OptionalMod.enabledByDefault].
     * Disabled mods contribute their `jars` (and `infoFile`, if any) to
     * the returned set.
     */
    fun calculateIgnoredFiles(profile: ServerProfile, userState: Map<String, Boolean>): Set<String>
}
