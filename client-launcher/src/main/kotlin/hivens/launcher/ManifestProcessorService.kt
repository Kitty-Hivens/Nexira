package hivens.launcher

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod
import kotlinx.serialization.json.* import org.slf4j.LoggerFactory

class ManifestProcessorService(
    private val json: Json
) : IManifestProcessorService {

    private val log = LoggerFactory.getLogger(ManifestProcessorService::class.java)

    override fun processManifest(version: String): FileManifest {
        // В текущей архитектуре манифест приходит уже готовым в SessionData,
        // этот метод в оригинале был заглушкой, оставляем заглушкой.
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
        val rawMods = profile.optionalModsData ?: return result

        rawMods.forEach { (modId, modData) ->
            try {
                val mod = json.decodeFromJsonElement<OptionalMod>(modData)

                // Заполняем пропуски, если их нет в JSON
                if (mod.id.isEmpty()) mod.id = modId
                if (mod.jars.isEmpty()) mod.jars = mutableListOf("$modId.jar")

                result.add(mod)
            } catch (e: Exception) {
                log.error("Ошибка парсинга конфигурации мода '$modId': ${e.message}")
            }
        }
        return result
    }
}
