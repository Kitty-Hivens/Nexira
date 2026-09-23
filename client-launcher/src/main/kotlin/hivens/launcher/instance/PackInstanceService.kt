package hivens.launcher.instance

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.io.InstanceMutationLock
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackSnapshotService
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
    private val running: RunningPackSource,
    private val work: InstanceWorkRegistry,
    private val snapshots: PackSnapshotService,
    private val journal: ApplyJournal,
    private val sizes: InstanceSizeService,
) {
    private val log = LoggerFactory.getLogger(PackInstanceService::class.java)

    private fun instanceDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    /** How a delete went. Only [Deleted] removed anything. */
    sealed interface DeleteOutcome {
        data object Deleted : DeleteOutcome

        /** The instance's game is running or being launched. Nothing was touched. */
        data object GameRunning : DeleteOutcome

        /** Something else is rewriting the instance. Nothing was touched. */
        data class Busy(val work: InstanceWork) : DeleteOutcome

        /** Some files would not go, so the registry entry was kept. */
        data object Incomplete : DeleteOutcome
    }

    /**
     * Remove the instance's files, then its registry entry -- in that order so a
     * file that will not go leaves the entry in place rather than orphaning data
     * on disk with the pack gone from the Library.
     *
     * Refused while the instance's game runs or other work rewrites it. Neither
     * used to be asked: on Linux the tree went from under a live game, which went
     * on writing its world into a directory that no longer existed, and on Windows
     * the locked jars stopped the delete halfway, and an update running beside it
     * kept writing into what was being removed. The delete is marked as work on
     * the instance for its length, so a launch arriving meanwhile is refused too,
     * and the running check is asked again once that mark is up.
     */
    suspend fun deleteCompletely(instance: PackInstance): DeleteOutcome = withContext(Dispatchers.IO) {
        refusal(instance.id)?.let { return@withContext it }
        work.during(instance.id, InstanceWork.Delete) {
            if (running.runningPackInstanceId.value == instance.id) return@during DeleteOutcome.GameRunning
            val dir = instanceDirOf(instance)
            InstanceMutationLock.withLock(dir) {
                if (deleteTree(dir)) {
                    repository.delete(instance.id)
                    // What was kept about the instance outside its directory goes with
                    // it. Left behind, up to three snapshots of its files stayed on disk
                    // for good, since the prune runs only inside an apply.
                    snapshots.deleteAll(instance.instanceDirName)
                    journal.complete(instance.instanceDirName)
                    sizes.forget(instance.id)
                    DeleteOutcome.Deleted
                } else {
                    DeleteOutcome.Incomplete
                }
            }
        }
    }

    private fun refusal(instanceId: String): DeleteOutcome? = when {
        running.runningPackInstanceId.value == instanceId -> DeleteOutcome.GameRunning
        else -> work.workOn(instanceId)?.let { DeleteOutcome.Busy(it) }
    }

    /**
     * Fork this instance into a Local one the user owns: flip origin to Local and
     * record where it came from in [PackInstance.forkedFrom] so provenance (and
     * its art) survive. Moves no files -- the same on-disk instance is now Local.
     *
     * The server binding goes too. Flipping only the origin left the cached
     * manifest's auth requirement in place, so the launch still treated the pack as
     * bound and swept `mods/` against the mirror's baseline, while the Content tab
     * opened adding and removing mods because the origin was now Local: whatever
     * the player added was deleted at Play. A pack whose contents are its owner's
     * is not one a server session can be lent to, so it launches unbound.
     */
    suspend fun detachToLocal(instance: PackInstance): PackInstance {
        val detach: (PackInstance) -> PackInstance = { current ->
            current.copy(
                packRef = current.packRef.copy(origin = PackOrigin.Local),
                forkedFrom = current.forkedFrom ?: current.packRef,
                cachedManifest = current.cachedManifest?.copy(authRequirement = null),
            )
        }
        return repository.update(instance.id, detach) ?: detach(instance)
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
