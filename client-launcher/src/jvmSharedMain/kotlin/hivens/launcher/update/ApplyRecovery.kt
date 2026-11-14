package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.io.InstanceMutationLock
import hivens.core.io.deleteTree
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import hivens.launcher.record.withBuildOf

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
    private val work: InstanceWorkRegistry,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(ApplyRecovery::class.java)

    /** Whether this process has cleared the kept reattach archives already. */
    private val archivesCleared = AtomicBoolean(false)

    /**
     * Roll back every journalled in-flight apply. Returns the recovered instance dir
     * names.
     *
     * Also clears the archives reattach previews kept, on the first call only. No
     * reattach outlives the process that started it, so whatever is there before the
     * first call belongs to nobody. A later call comes from a shell made again in the
     * same process, a crash reload or a new Activity on a phone, while a reattach
     * started earlier may still be fetching into that folder.
     */
    suspend fun recoverInterrupted(): List<String> = withContext(io) {
        if (archivesCleared.compareAndSet(false, true)) {
            runCatching { deleteTree(reattachArchiveRoot(dataDir), clearReadOnly = false) }
                .onFailure { log.warn("apply-recovery: could not clear the kept reattach archives", it) }
        }
        journal.listPending().mapNotNull { listed ->
            // Marked for its whole length, so the launch controls say why this pack
            // cannot start yet instead of letting it start over a half-rolled-back
            // instance.
            work.during(listed.instanceId, InstanceWork.Recovery) { recoverOne(listed) }
        }
    }

    /** Rolls one instance back, and answers its dir name when it did. */
    private suspend fun recoverOne(listed: PendingApply): String? {
        val clientDir = dataDir.resolve("instances").resolve(listed.instanceDirName)
        return InstanceMutationLock.withLock(clientDir) {
            // Read again under the lock. The listing was taken before it, and an
            // apply that got the lock first may have committed since and cleared
            // the marker: restoring the listed snapshot then would put an older
            // build over one that had just landed. A marker that is still there,
            // whichever apply wrote it, is one that did not finish.
            val entry = journal.read(listed.instanceDirName) ?: run {
                log.info("apply-recovery: {} finished since the listing, nothing to roll back", listed.instanceDirName)
                return@withLock null
            }
            val rolledBack = try {
                // Read before the restore, which is followed by the snapshot's deletion.
                val reattach = snapshotService.takenByReattach(entry.instanceDirName, entry.snapshotId)
                val restored = snapshotService.restore(clientDir, entry.instanceDirName, entry.snapshotId, entry.managedPaths.toSet())
                // Pin: a recovered instance stops following latest so a reproducible
                // bad build is not re-applied (and re-crashed) on the next pass.
                repository.update(restored.id) { it.withBuildOf(restored, provenance = reattach).copy(followLatest = false) }
                snapshotService.delete(entry.instanceDirName, entry.snapshotId)
                log.warn(
                    "apply-recovery: instance {} had an update to {} interrupted; rolled back to {}",
                    entry.instanceDirName, entry.toVersion, entry.fromVersion,
                )
                entry.instanceDirName
            } catch (e: CancellationException) {
                // Quitting mid-rollback says nothing about whether this apply can be
                // recovered, so it must not spend the marker. The marker is the only
                // thing that brings us back here; clearing it on the way out would
                // leave the instance half-updated with the registry still on the old
                // version, and nothing would ever look at it again.
                throw e
            } catch (e: Throwable) {
                // Keep the snapshot for the Version screen's manual restore, but the
                // marker is cleared below so a corrupt / unrecoverable apply does not
                // re-run every boot.
                log.error("apply-recovery: could not roll back {}; snapshot left for a manual restore", entry.instanceDirName, e)
                null
            }
            journal.complete(entry.instanceDirName)
            rolledBack
        }
    }
}
