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

    /** Flip a content item on/off by adding or removing the `.disabled` suffix. */
    suspend fun setEnabled(instanceDir: Path, kind: ContentKind, fileName: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            InstanceMutationLock.withLock(instanceDir) {
                val dir = instanceDir.resolve(kind.folderName())
                val on = dir.resolve(fileName)
                val off = dir.resolve(fileName + DISABLED_SUFFIX)
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
                val dir = instanceDir.resolve(kind.folderName())
                runCatching {
                    fileOpRetry("delete $fileName") {
                        Files.deleteIfExists(dir.resolve(fileName))
                        Files.deleteIfExists(dir.resolve(fileName + DISABLED_SUFFIX))
                    }
                }.onFailure { log.warn("Delete {} failed: {}", fileName, it.message) }
            }
        }

    /**
     * Put [source] in place of [oldFileName], carrying the old file's on/off
     * state onto the new one.
     *
     * Order matters and is deliberate: the replacement is moved in FIRST, the
     * old file removed after. The other way round, a move that fails after the
     * delete leaves the instance without a mod it had a second ago and nothing
     * to put back. This way the worst case is the pair coexisting for the length
     * of one retry loop -- which the rescan then shows, and which the caller
     * reports as a failed update rather than a silent loss.
     *
     * A disabled item stays disabled: a player who turned a mod off and then
     * updated the folder did not ask for it back.
     */
    suspend fun replace(
        instanceDir: Path,
        kind: ContentKind,
        oldFileName: String,
        source: Path,
        newFileName: String,
        enabled: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        InstanceMutationLock.withLock(instanceDir) {
            val dir = instanceDir.resolve(kind.folderName())
            val target = dir.resolve(if (enabled) newFileName else newFileName + DISABLED_SUFFIX)
            runCatching {
                Files.createDirectories(dir)
                fileOpRetry("install $newFileName") {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
                if (newFileName != oldFileName) {
                    fileOpRetry("retire $oldFileName") {
                        Files.deleteIfExists(dir.resolve(oldFileName))
                        Files.deleteIfExists(dir.resolve(oldFileName + DISABLED_SUFFIX))
                    }
                }
                true
            }.getOrElse {
                log.warn("Replacing {} with {} failed: {}", oldFileName, newFileName, it.message)
                // The replacement never landed anywhere the game would load it,
                // so take the scratch copy down with the failure.
                runCatching { Files.deleteIfExists(source) }
                false
            }
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
                val dir = instanceDir.resolve(kind.folderName())
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
