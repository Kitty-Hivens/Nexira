package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.io.InstanceMutationLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Startup recovery for updates interrupted by a hard crash (kill / power loss /
 * OOM). An [ApplyJournal] marker that outlived the process means the apply mutated
 * files but never committed -- the instance is half-updated with the registry still
 * on the old version. This restores each such instance from its pre-update snapshot
 * under the mutation lock and pins it (stops following latest) so the auto-updater
 * does not immediately re-run the update that just crashed. Runs regardless of the
 * auto-update setting; the [InstanceMutationLock] serialises it against a concurrent
 * auto-update pass or launch.
 */
class ApplyRecovery(
    private val snapshotService: PackSnapshotService,
    private val repository: IPackRepository,
    private val journal: ApplyJournal,
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(ApplyRecovery::class.java)

    /** Roll back every journalled in-flight apply. Returns the recovered instance dir names. */
    suspend fun recoverInterrupted(): List<String> = withContext(Dispatchers.IO) {
        val recovered = ArrayList<String>()
        for (entry in journal.listPending()) {
            val clientDir = dataDir.resolve("instances").resolve(entry.instanceDirName)
            InstanceMutationLock.withLock(clientDir) {
                runCatching {
                    val restored = snapshotService.restore(clientDir, entry.instanceDirName, entry.snapshotId, entry.managedPaths.toSet())
                    // Pin: a recovered instance stops following latest so a reproducible
                    // bad build is not re-applied (and re-crashed) on the next pass.
                    repository.put(restored.copy(followLatest = false))
                    snapshotService.delete(entry.instanceDirName, entry.snapshotId)
                    log.warn(
                        "apply-recovery: instance {} had an update to {} interrupted; rolled back to {}",
                        entry.instanceDirName, entry.toVersion, entry.fromVersion,
                    )
                    recovered += entry.instanceDirName
                }.onFailure {
                    // Keep the snapshot for the Version screen's manual restore, but the
                    // marker is cleared below so a corrupt / unrecoverable apply does not
                    // re-run every boot.
                    log.error("apply-recovery: could not roll back {}; snapshot left for a manual restore", entry.instanceDirName, it)
                }
                journal.complete(entry.instanceDirName)
            }
        }
        recovered
    }
}
