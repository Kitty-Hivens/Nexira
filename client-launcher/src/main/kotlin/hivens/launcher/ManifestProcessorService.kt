package hivens.launcher

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class ManifestProcessorService(
    private val json: Json
) : IManifestProcessorService {

    private val log = LoggerFactory.getLogger(ManifestProcessorService::class.java)

    override fun flattenManifest(manifest: FileManifest): Map<String, FileData> {
        val result = HashMap<String, FileData>()
        flattenRecursive(manifest, "", result)
        return result
    }

    private fun flattenRecursive(manifest: FileManifest, currentPath: String, result: MutableMap<String, FileData>) {
        manifest.files.forEach { (k, v) ->
            result[currentPath + k] = v
        }
        manifest.directories.forEach { (k, v) ->
            flattenRecursive(v, "$currentPath$k/", result)
        }
    }

    override fun getOptionalModsForClient(profile: ServerProfile): List<OptionalMod> {
        val result = ArrayList<OptionalMod>()
        val rawMods = profile.optionalModsData ?: return result

        rawMods.forEach { (modId, modData) ->
            try {
                val decoded = json.decodeFromJsonElement<OptionalMod>(modData)
                // Defaulting layer: upstream sometimes omits `id` and `jars`
                // entirely, expecting the manifest key to stand in for them.
                // We patch via copy() rather than mutating since OptionalMod
                // fields are now `val`.
                val mod = decoded.copy(
                    id   = decoded.id.ifEmpty { modId },
                    jars = decoded.jars.ifEmpty { listOf("$modId.jar") },
                )
                result.add(mod)
            } catch (e: Exception) {
                log.error("Error parsing mod configuration '$modId': ${e.message}")
            }
        }
        return result
    }

    override fun calculateIgnoredFiles(profile: ServerProfile, userState: Map<String, Boolean>): Set<String> {
        val available = getOptionalModsForClient(profile)
        if (available.isEmpty()) return emptySet()
        val ignored = HashSet<String>()
        for (mod in available) {
            val isEnabled = userState[mod.id] ?: mod.isDefault
            if (!isEnabled) {
                ignored.addAll(mod.jars)
                if (mod.infoFile != null) ignored.add(mod.infoFile!!)
            }
        }
        return ignored
    }
}
