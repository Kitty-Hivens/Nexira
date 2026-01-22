package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod

interface IManifestProcessorService {

    fun processManifest(version: String): FileManifest?

    /**
     * Recursively "flattens" the tree file manifest.
     * @return Flat map path -> data.
     */
    fun flattenManifest(manifest: FileManifest): Map<String, FileData>

    /**
     * Returns a list of optional modifications for a specific profile.
     */
    fun getOptionalModsForClient(profile: ServerProfile): List<OptionalMod>
}
