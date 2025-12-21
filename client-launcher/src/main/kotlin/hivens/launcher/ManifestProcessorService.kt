package hivens.launcher

import com.google.gson.Gson
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod
import org.slf4j.LoggerFactory
import java.util.ArrayList
import java.util.HashMap

class ManifestProcessorService(private val gson: Gson) : IManifestProcessorService {

    private val log = LoggerFactory.getLogger(ManifestProcessorService::class.java)

    override fun processManifest(version: String): FileManifest {
        return FileManifest()
    }

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
        val rawMods = profile.optionalModsData

        if (rawMods.isNullOrEmpty()) {
            return result
        }

        for ((modId, modData) in rawMods) {
            try {
                val json = gson.toJson(modData)
                val mod = gson.fromJson(json, OptionalMod::class.java)

                if (mod.id.isEmpty()) mod.id = modId
                if (mod.jars.isEmpty()) {
                    mod.jars = mutableListOf("$modId.jar")
                }

                result.add(mod)
            } catch (e: Exception) {
                log.error("Failed to parse mod {}", modId, e)
            }
        }
        return result
    }
}
