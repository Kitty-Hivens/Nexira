package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * What every update path does around the part that actually rewrites an instance:
 * a snapshot to return to, a journal marker that outlives a crash, and a rollback
 * when the rewrite does not finish.
 *
 * One implementation for both sources. The mirror path had all of it and the
 * Modrinth path had a snapshot for structural changes only, no marker and no
 * rollback, so an installer that threw left a half-converted instance and an
 * unclaimed snapshot, and a crash left one that nothing would ever look at.
 *
 * The caller holds [hivens.core.io.InstanceMutationLock] for the instance.
 */
internal class ApplyGuard(
    private val snapshots: PackSnapshotService,
    private val journal: ApplyJournal,
    private val repository: IPackRepository,
    private val keepSnapshots: Int = KEEP_SNAPSHOTS,
) {
    private val log = LoggerFactory.getLogger(ApplyGuard::class.java)

    /**
     * Runs [rewrite] between a snapshot of [managed] and its commit.
     *
     * [rewrite] must include the registry write that commits the new build: the
     * marker is cleared only once it returns, so a crash before that point is rolled
     * back on the next start, and a crash after it finds nothing to undo.
     *
     * On any failure, cancellation included, the snapshot is restored and the
     * pre-update record put back before the failure goes on. That runs outside the
     * caller's cancellation: a restore cut short leaves exactly the half-updated
     * instance it exists to prevent. When the restore itself fails, the marker is
     * kept, so the next start's recovery gets a second attempt, and the snapshot
     * stays for a manual one.
     */
    suspend fun <T> apply(
        clientDir: Path,
        instance: PackInstance,
        managed: Set<String>,
        fromVersion: String?,
        toVersion: String,
        rewrite: suspend () -> T,
    ): T {
        val now = Instant.now().toEpochMilli()
        val snapshot = snapshots.capture(clientDir, instance, managed, "$now-${UUID.randomUUID().toString().take(8)}", now)
        // Before the first file write, so a hard crash from here to the commit is
        // rolled back on the next start instead of leaving a half-updated pack.
        journal.begin(
            PendingApply(
                instanceId = instance.id,
                instanceDirName = instance.instanceDirName,
                snapshotId = snapshot.id,
                fromVersion = fromVersion,
                toVersion = toVersion,
                managedPaths = managed.toList(),
                startedAtEpoch = snapshot.createdAtEpoch,
            )
        )
        val result = try {
            rewrite()
        } catch (e: Throwable) {
            withContext(NonCancellable) { rollBack(clientDir, instance.instanceDirName, snapshot.id, managed, e) }
            throw e
        }
        withContext(NonCancellable) { journal.complete(instance.instanceDirName) }
        snapshots.prune(instance.instanceDirName, keepSnapshots)
        return result
    }

    private suspend fun rollBack(clientDir: Path, instanceDirName: String, snapshotId: String, managed: Set<String>, cause: Throwable) {
        try {
            repository.put(snapshots.restore(clientDir, instanceDirName, snapshotId, managed))
            snapshots.delete(instanceDirName, snapshotId)
            journal.complete(instanceDirName)
            log.warn("update: {} did not finish ({}) and was rolled back to the snapshot", instanceDirName, cause.toString())
        } catch (restoreFailure: Throwable) {
            // Logged here rather than only attached to the original: when that one is
            // a cancellation, nothing downstream logs it, and this is the failure that
            // leaves an instance neither build.
            log.error(
                "update: {} did not finish and the rollback failed too, so the marker stays for the next start",
                instanceDirName, restoreFailure,
            )
            cause.addSuppressed(restoreFailure)
        }
    }

    companion object {
        const val KEEP_SNAPSHOTS = 3
    }
}
