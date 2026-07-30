package hivens.launcher.instance

import hivens.core.io.InstanceMutationLock
import hivens.core.io.fileOpRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * Direct file operations on an instance's own content folders, the counterpart
 * to [InstanceContentScanner]. Enable/disable is a `.disabled` rename (no
 * re-download); delete removes the file; add copies a jar/zip in. Origin-agnostic
 * -- the caller gates these behind detach for pack-tracked instances.
 */
class InstanceContentManager {

    private val log = LoggerFactory.getLogger(InstanceContentManager::class.java)

    private fun folderFor(kind: ContentKind): String = when (kind) {
        ContentKind.Mod -> "mods"
        ContentKind.ResourcePack -> "resourcepacks"
        ContentKind.ShaderPack -> "shaderpacks"
    }

    /** Flip a content item on/off by adding or removing the `.disabled` suffix. */
    suspend fun setEnabled(instanceDir: Path, kind: ContentKind, fileName: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            InstanceMutationLock.withLock(instanceDir) {
                val dir = instanceDir.resolve(folderFor(kind))
                val on = dir.resolve(fileName)
                val off = dir.resolve("$fileName.disabled")
                runCatching {
                    fileOpRetry("toggle $fileName") {
                        if (enabled) {
                            if (Files.exists(off)) Files.move(off, on, StandardCopyOption.ATOMIC_MOVE)
                        } else {
                            if (Files.exists(on)) Files.move(on, off, StandardCopyOption.ATOMIC_MOVE)
                        }
                    }
                }.onFailure { log.warn("Toggle {} ({}) failed: {}", fileName, enabled, it.message) }
            }
        }

    /** Remove the item, whether currently enabled or `.disabled`. */
    suspend fun delete(instanceDir: Path, kind: ContentKind, fileName: String) =
        withContext(Dispatchers.IO) {
            InstanceMutationLock.withLock(instanceDir) {
                val dir = instanceDir.resolve(folderFor(kind))
                runCatching {
                    fileOpRetry("delete $fileName") {
                        Files.deleteIfExists(dir.resolve(fileName))
                        Files.deleteIfExists(dir.resolve("$fileName.disabled"))
                    }
                }.onFailure { log.warn("Delete {} failed: {}", fileName, it.message) }
            }
        }

    /**
     * Copy [sources] into the instance's [kind] folder, skipping a name that
     * already exists so an accidental re-add never clobbers an installed file.
     * Returns how many landed.
     */
    suspend fun addFiles(instanceDir: Path, kind: ContentKind, sources: List<Path>): Int =
        withContext(Dispatchers.IO) {
            InstanceMutationLock.withLock(instanceDir) {
                val dir = instanceDir.resolve(folderFor(kind))
                Files.createDirectories(dir)
                sources.count { src ->
                    val target = dir.resolve(src.name)
                    runCatching {
                        if (Files.exists(target)) false
                        else { fileOpRetry("add ${src.name}") { Files.copy(src, target) }; true }
                    }.getOrElse { log.warn("Add {} failed: {}", src, it.message); false }
                }
            }
        }
}
