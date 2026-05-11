package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod

interface IManifestProcessorService {
    /**
     * Recursively "flattens" the tree file manifest.
     * @return Flat map path -> data.
     */
    fun flattenManifest(manifest: FileManifest): Map<String, FileData>

    /**
     * Returns a list of optional modifications for a specific profile.
     */
    fun getOptionalModsForClient(profile: ServerProfile): List<OptionalMod>

    /**
     * Computes the set of jar names to exclude from sync based on which optional
     * mods the user has unchecked.
     *
     * [userState] maps `mod.id → enabled?`. If a mod isn't in the map we fall
     * back to its `isDefault`. Disabled mods contribute their `jars` (and
     * `infoFile`, if any) to the ignored set.
     *
     * Both LauncherController (per-server launch sync) and AutoSyncService
     * (background sync of all installed packs) call this — keeping it here
     * keeps the two pipelines from drifting on what counts as ignored.
     */
    fun calculateIgnoredFiles(profile: ServerProfile, userState: Map<String, Boolean>): Set<String>
}
