package hivens.launcher.instance

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Instance-level mutations that reach past the registry: a full delete (files on
 * disk THEN the registry entry) and a detach-to-Local fork. Plain field edits
 * (rename, notes, runtime) stay a direct [IPackRepository.put] at the call site;
 * these two live here because the delete's fs half must be ordered against the
 * registry, and both were previously duplicated inline across the Library and
 * detail surfaces.
 */
class PackInstanceService(
    private val repository: IPackRepository,
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(PackInstanceService::class.java)

    private fun instanceDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    /**
     * Remove the instance's files, then its registry entry -- in that order so a
     * locked file (a running game holding a jar) leaves the entry in place rather
     * than orphaning data on disk with the pack gone from the Library. Returns
     * true only when every file was removed and the entry dropped.
     */
    suspend fun deleteCompletely(instance: PackInstance): Boolean = withContext(Dispatchers.IO) {
        val removed = deleteTree(instanceDirOf(instance))
        if (removed) repository.delete(instance.id)
        removed
    }

    /**
     * Fork this instance into a Local one the user owns: flip origin to Local and
     * record where it came from in [PackInstance.forkedFrom] so provenance (and
     * its art) survive. Moves no files -- the same on-disk instance is now Local.
     */
    suspend fun detachToLocal(instance: PackInstance): PackInstance {
        val detached = instance.copy(
            packRef = instance.packRef.copy(origin = PackOrigin.Local),
            forkedFrom = instance.forkedFrom ?: instance.packRef,
        )
        repository.put(detached)
        return detached
    }

    /**
     * Recursive delete, deepest-first so directories are empty before removal.
     * Returns true only when every entry was removed; a failed entry is logged
     * and leaves the tree partial so the caller keeps the registry entry rather
     * than orphaning files with the pack gone from the list.
     */
    private fun deleteTree(dir: Path): Boolean {
        if (!Files.exists(dir)) return true
        var ok = true
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.delete(path) }.onFailure { e ->
                    ok = false
                    log.warn("delete instance dir: could not remove {} -- {}", path, e.toString())
                }
            }
        }
        return ok
    }
}
