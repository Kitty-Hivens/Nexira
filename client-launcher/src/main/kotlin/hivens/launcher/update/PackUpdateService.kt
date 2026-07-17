package hivens.launcher.update

import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.toBaselineManifest
import hivens.core.api.dto.smrt.toDomain
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileManifest
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.flatten
import hivens.core.io.InstanceMutationLock
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.core.update.CompatChange
import hivens.core.update.UpdateReconciler
import hivens.core.update.classifyCompat
import hivens.core.update.comparePackVersions
import hivens.core.update.isNewerPackVersion
import hivens.launcher.ProtectedPaths
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Drives a mirror pack instance from its installed build to another one -- a
 * forward update to the mirror's latest, or a switch/rollback to a specific
 * version. [checkForUpdate] previews the change read-only; [applyUpdate] fetches,
 * reconciles, grades, applies, and commits under [InstanceMutationLock] so it
 * cannot interleave with a sync or an optional-toggle relabel.
 *
 * Only mirror-origin instances are meaningful; the reconcile treats a null
 * baseline (a pre-baseline or non-mirror install) as an add/update-only plan
 * graded amber via [hivens.core.update.CompatChange.Unknown]. Failures (network,
 * sha1 mismatch, IO) throw, consistent with the fail-loud sync path.
 *
 * `pinnedPackVersion` is read here as the CURRENT installed version (what launch
 * fetches the matching manifest for); an applied update advances it. The
 * follow-latest vs pinned update POLICY is a separate concern layered on later.
 */
class PackUpdateService(
    private val client: SmrtPackClient,
    private val syncService: SmrtSyncService,
    private val repository: IPackRepository,
    private val protectedPaths: ProtectedPaths,
    private val snapshotService: PackSnapshotService,
    private val dataDir: Path,
) : PackUpdater {
    private val log = LoggerFactory.getLogger(PackUpdateService::class.java)

    private fun clientDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    private fun currentVersionOf(instance: PackInstance): String? =
        instance.pinnedPackVersion ?: instance.packRef.version

    /**
     * Read-only preview: is a newer build available, and what would applying it
     * do? Advisory -- it scans disk without the mutation lock; the authoritative
     * plan is recomputed under the lock in [applyUpdate].
     */
    override suspend fun checkForUpdate(instance: PackInstance): UpdateCheck = withContext(Dispatchers.IO) {
        val packId = instance.packRef.id
        val latest = client.fetchSummary(packId).latestPackVersion
        val current = currentVersionOf(instance)
        if (current != null && !isNewerPackVersion(latest, current)) {
            return@withContext UpdateCheck.UpToDate
        }
        previewFor(instance, client.fetchManifest(packId))
    }

    /**
     * Apply an update. [targetVersion] null updates to the mirror's latest; a
     * specific version switches/rolls back to that build. Re-reads the instance
     * and re-scans under [InstanceMutationLock] so the applied plan is
     * self-consistent, then commits the new baseline, version, cached snapshot,
     * and carried-over optional-toggle set.
     */
    override suspend fun applyUpdate(
        instance: PackInstance,
        targetVersion: String?,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): UpdateOutcome {
        val packId = instance.packRef.id
        val target = if (targetVersion != null) {
            client.fetchManifestVersion(packId, targetVersion)
        } else {
            client.fetchManifest(packId)
        }
        if (currentVersionOf(instance) == target.packVersion) {
            return UpdateOutcome.AlreadyCurrent
        }
        val clientDir = clientDirOf(instance)
        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val fresh = repository.get(instance.id) ?: instance
                val targetManifest = target.toBaselineManifest()
                val paths = fresh.installedManifest?.flatten()?.keys.orEmpty() + targetManifest.flatten().keys
                val plan = UpdateReconciler.reconcile(
                    baseline = fresh.installedManifest,
                    target = targetManifest,
                    current = scanInstanceState(clientDir, paths),
                    isProtected = protectedPaths::isProtected,
                )
                val compat = gradeCompat(fresh, target)
                val enabledState = OptionalContentRules.enabledState(target.mods, fresh.optionalContent)
                // Snapshot the pack-managed files before ANY change so a failed apply
                // auto-reverts and a structural / no-baseline update stays recoverable
                // (hardlinks make it cheap; a label-only no-op skips it).
                val managed = managedRealPaths(fresh.installedManifest, targetManifest)
                val snapshot = if (!plan.isEmpty) {
                    val now = Instant.now().toEpochMilli()
                    snapshotService.capture(clientDir, fresh, managed, "$now-${UUID.randomUUID().toString().take(8)}", now)
                } else {
                    null
                }
                try {
                    syncService.applyUpdate(clientDir, target, plan, enabledState, progress)
                    commit(fresh, target, enabledState, pinExplicit = targetVersion != null)
                } catch (e: Throwable) {
                    if (snapshot != null) {
                        repository.put(snapshotService.restore(clientDir, fresh.instanceDirName, snapshot.id, managed))
                        snapshotService.delete(fresh.instanceDirName, snapshot.id)
                    }
                    throw e
                }
                if (snapshot != null) snapshotService.prune(fresh.instanceDirName, KEEP_SNAPSHOTS)
                log.info(
                    "update: pack={} {} -> {} ({} add, {} update, {} delete, {} conflict, compat={})",
                    packId, currentVersionOf(fresh), target.packVersion,
                    plan.toAdd.size, plan.toUpdate.size, plan.toDelete.size, plan.conflicts.size, compat,
                )
                UpdateOutcome.Applied(target.packVersion, compat, plan)
            }
        }
    }

    private fun previewFor(instance: PackInstance, target: SmrtPackManifest): UpdateCheck {
        val targetManifest = target.toBaselineManifest()
        val paths = instance.installedManifest?.flatten()?.keys.orEmpty() + targetManifest.flatten().keys
        val plan = UpdateReconciler.reconcile(
            baseline = instance.installedManifest,
            target = targetManifest,
            current = scanInstanceState(clientDirOf(instance), paths),
            isProtected = protectedPaths::isProtected,
        )
        return UpdateCheck.Available(currentVersionOf(instance), target.packVersion, gradeCompat(instance, target), plan)
    }

    /**
     * Grades how structural the change is. A null baseline (a pre-baseline or
     * non-mirror install) is treated as [CompatChange.Unknown] -- the reconcile
     * cannot tell a user edit from a pack change, so it must snapshot before and be
     * held under the amber policy rather than silently overwriting a hand-edited config.
     */
    private fun gradeCompat(instance: PackInstance, target: SmrtPackManifest): CompatChange =
        if (instance.installedManifest == null) {
            CompatChange.Unknown
        } else {
            classifyCompat(instance.cachedManifest, target.minecraft.version, target.loader.name, target.loader.version)
        }

    /**
     * Persist the applied build. [pinExplicit] (a switch/rollback to a specific
     * version) also stops following latest, so the auto-updater does not undo a
     * deliberate downgrade on the next startup.
     */
    private suspend fun commit(
        instance: PackInstance,
        target: SmrtPackManifest,
        enabledState: Map<String, Boolean>,
        pinExplicit: Boolean,
    ) {
        repository.put(
            instance.copy(
                packRef = instance.packRef.copy(version = target.packVersion),
                pinnedPackVersion = target.packVersion,
                followLatest = if (pinExplicit) false else instance.followLatest,
                installedManifest = target.toBaselineManifest(),
                cachedManifest = CachedManifestSnapshot(
                    minecraftVersion = target.minecraft.version,
                    loaderName = target.loader.name,
                    loaderVersion = target.loader.version,
                    javaMajor = target.java.major,
                    authRequirement = target.auth?.toDomain(),
                ),
                optionalContent = OptionalContentRules.togglesFrom(target.mods, enabledState),
            )
        )
    }

    /** The mirror's available builds for [instance], newest first, for a version switch or rollback. */
    suspend fun availableVersions(instance: PackInstance): List<String> = withContext(Dispatchers.IO) {
        client.listVersions(instance.packRef.id).sortedWith { a, b -> comparePackVersions(b, a) }
    }

    /** Snapshots [instance] can be rolled back to, newest first. */
    fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
        snapshotService.list(instance.instanceDirName)

    /**
     * Roll [instance] back to snapshot [snapshotId]: restore the captured files
     * and the pre-update instance record under the mutation lock. Returns the
     * restored instance.
     */
    suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
        val clientDir = clientDirOf(instance)
        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: instance
                val managed = managedRealPaths(null, current.installedManifest ?: FileManifest())
                val restored = snapshotService.restore(clientDir, current.instanceDirName, snapshotId, managed)
                // A rollback is a deliberate pin: stop following latest so the update we
                // just undid is not re-applied on the next startup.
                val pinned = restored.copy(followLatest = false)
                repository.put(pinned)
                pinned
            }
        }
    }

    private companion object {
        private const val KEEP_SNAPSHOTS = 3
    }
}
