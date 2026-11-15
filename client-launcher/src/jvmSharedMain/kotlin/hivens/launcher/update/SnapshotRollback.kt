package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.FileManifest
import hivens.core.data.PackInstance
import hivens.core.io.InstanceMutationLock
import hivens.core.update.PackSnapshot
import hivens.launcher.record.PackFileRecord
import hivens.launcher.record.withBuildOf
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rolls a pack back to one of its snapshots with nothing from its source, for a pack whose
 * source cannot be asked: its module is not loaded, or it brings no updater.
 *
 * A snapshot holds the files and the record of the build it was taken on, so putting it
 * back needs no source, as the startup recovery puts one back. What the restore may
 * remove is every file the record says a build placed, from a manifest or from an
 * archive, since which of the two this pack's source used is the source's to say. The
 * names of switchable files stay as restored, and the next update or sync of a loaded
 * source puts them under the names the player's choice gives them.
 */
class SnapshotRollback(
    private val snapshots: PackSnapshotService,
    private val repository: IPackRepository,
    private val dataDir: Path,
) {
    /** Snapshots [instance] can be rolled back to, newest first. */
    fun list(instance: PackInstance): List<PackSnapshot> = snapshots.list(instance.instanceDirName)

    /**
     * Restores snapshot [snapshotId] of [instance] and its build onto the record, under
     * the instance's lock, pinned the way every rollback pins. Returns the record as written.
     */
    suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                val managed = managedRealPaths(null, current.installedManifest ?: FileManifest()) +
                    PackFileRecord.read(clientDir).keys + PackFileRecord.FILE_NAME
                val reattach = snapshots.takenByReattach(current.instanceDirName, snapshotId)
                val restored = snapshots.restore(clientDir, current.instanceDirName, snapshotId, managed)
                repository.update(current.id) { it.withBuildOf(restored, provenance = reattach).copy(followLatest = false) }
                    ?: current.withBuildOf(restored, provenance = reattach).copy(followLatest = false)
            }
        }
    }
}
