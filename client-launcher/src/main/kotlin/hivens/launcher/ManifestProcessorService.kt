package hivens.launcher

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod
import hivens.core.data.flatten

class ManifestProcessorService : IManifestProcessorService {

    override fun flattenManifest(manifest: FileManifest): Map<String, FileData> = manifest.flatten()

    /**
     * Upstream omits `id` and `jars` on a mod whose key already says both,
     * so the key stands in for them. Applied on the way out rather than when
     * the profile is built, because a profile served from the disk cache was
     * written before this rule existed and needs it too.
     */
    override fun getOptionalModsForClient(profile: ServerProfile): List<OptionalMod> =
        profile.optionalMods.map { (modId, mod) ->
            mod.copy(
                id   = mod.id.ifEmpty { modId },
                jars = mod.jars.ifEmpty { listOf("$modId.jar") },
            )
        }

    override fun calculateIgnoredFiles(profile: ServerProfile, userState: Map<String, Boolean>): Set<String> {
        val available = getOptionalModsForClient(profile)
        if (available.isEmpty()) return emptySet()
        val ignored = HashSet<String>()
        for (mod in available) {
            val isEnabled = userState[mod.id] ?: mod.enabledByDefault
            if (!isEnabled) {
                ignored.addAll(mod.jars)
                if (mod.infoFile != null) ignored.add(mod.infoFile!!)
            }
        }
        return ignored
    }
}
