package hivens.launcher.component

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.OptionalMod
import hivens.launcher.util.ClientFileHelper
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ModManager(private val manifestProcessor: IManifestProcessorService) {
    private val log = LoggerFactory.getLogger(ModManager::class.java)

    /**
     * Синхронизирует папку mods: оставляет обязательные моды + выбранные опциональные.
     */
    @Throws(IOException::class)
    fun syncMods(
        clientRoot: Path,
        profile: InstanceProfile,
        allMods: List<OptionalMod>,
        manifest: FileManifest,
        clientVersion: String
    ) {
        val modsDir = clientRoot.resolve("mods")
        ClientFileHelper.ensureDirectoryExists(modsDir)

        // 1. Собираем список всех файлов, которые должны остаться (Whitelist)
        val allowedFiles = HashSet<String>()
        
        // Добавляем обязательные файлы из манифеста (те, что лежат в папке mods/)
        val optionalJarNames = allMods.flatMap { it.jars }.toSet()
        
        val flatFiles = manifestProcessor.flattenManifest(manifest)
        for (path in flatFiles.keys) {
            if (path.contains("/mods/") || path.startsWith("mods/")) {
                val fileName = File(path).name
                // Если файл из манифеста не является частью опциональной модификации — он обязательный
                if (!optionalJarNames.contains(fileName)) {
                    allowedFiles.add(fileName)
                }
            }
        }

        // Добавляем включенные опциональные моды
        val state = profile.optionalModsState
        for (mod in allMods) {
            // Мод включен, если он есть в конфиге как true, или если его нет в конфиге, но он дефолтный
            val isEnabled = state.getOrDefault(mod.id, mod.isDefault)
            if (isEnabled) {
                allowedFiles.addAll(mod.jars)
            }
        }

        log.info("Syncing mods. Allowed jars count: {}", allowedFiles.size)

        // 2. Чистим основную папку mods
        ClientFileHelper.cleanDirectory(modsDir, allowedFiles, log)

        // 3. Если есть папка версии (например mods/1.12.2), чистим и её
        if (clientVersion.isNotEmpty()) {
            val versionModsDir = modsDir.resolve(clientVersion)
            if (Files.exists(versionModsDir)) {
                ClientFileHelper.cleanDirectory(versionModsDir, allowedFiles, log)
            }
        }
    }
}
