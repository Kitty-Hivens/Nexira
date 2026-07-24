package hivens.launcher.update

import hivens.core.api.dto.smrt.SmrtManifestBuild
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.toBaselineManifest
import hivens.core.api.dto.smrt.toDomain
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileManifest
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.fileManifestOf
import hivens.core.data.flatten
import hivens.core.io.InstanceMutationLock
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.core.update.UpdatePlan
import hivens.core.update.CompatChange
import hivens.core.update.UpdateReconciler
import hivens.core.update.classifyCompat
import hivens.core.update.mergedWith
import hivens.core.update.reconcileMods
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
    private val journal: ApplyJournal,
    private val dataDir: Path,
) : PackUpdater {
    private val log = LoggerFactory.getLogger(PackUpdateService::class.java)

    private fun clientDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    private fun currentVersionOf(instance: PackInstance): String? =
        instance.pinnedPackVersion ?: instance.packRef.version

    /**
     * Read-only preview: is a different build current on the mirror, and what
     * would applying it do? Detection is label INEQUALITY, not tuple ordering:
     * versions are canonical strings, channel builds (`SNAPSHOT-...`) do not
     * tuple-compare against releases, and a mirror-side rollback of latest is
     * also a change to surface. Advisory -- it scans disk without the mutation
     * lock; the authoritative plan is recomputed under the lock in [applyUpdate].
     */
    override suspend fun checkForUpdate(instance: PackInstance): UpdateCheck = withContext(Dispatchers.IO) {
        val packId = instance.packRef.id
        val latest = client.fetchSummary(packId).latestPackVersion
        val current = currentVersionOf(instance)
        if (current != null && latest == current) {
            return@withContext UpdateCheck.UpToDate
        }
        previewFor(instance, client.fetchManifest(packId))
    }

    /**
     * Read-only preview of a switch to the specific [targetVersion], forward or
     * backward. Same advisory caveat as [checkForUpdate].
     */
    override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck = withContext(Dispatchers.IO) {
        if (currentVersionOf(instance) == targetVersion) {
            return@withContext UpdateCheck.UpToDate
        }
        previewFor(instance, client.fetchManifestVersion(instance.packRef.id, targetVersion))
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
                val plan = computePlan(fresh, target, targetManifest, scanInstanceState(clientDir, paths))
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
                // Journal the in-flight apply (snapshot id + managed set) BEFORE the
                // first file write, so a hard crash between here and the commit is
                // rolled back on the next start instead of leaving a half-updated pack.
                if (snapshot != null) {
                    journal.begin(
                        PendingApply(
                            instanceId = fresh.id,
                            instanceDirName = fresh.instanceDirName,
                            snapshotId = snapshot.id,
                            fromVersion = currentVersionOf(fresh),
                            toVersion = target.packVersion,
                            managedPaths = managed.toList(),
                            startedAtEpoch = snapshot.createdAtEpoch,
                        )
                    )
                }
                try {
                    syncService.applyUpdate(clientDir, target, plan, enabledState, progress)
                    commit(fresh, target, enabledState, pinExplicit = targetVersion != null)
                    if (snapshot != null) journal.complete(fresh.instanceDirName)
                } catch (e: Throwable) {
                    if (snapshot != null) {
                        try {
                            repository.put(snapshotService.restore(clientDir, fresh.instanceDirName, snapshot.id, managed))
                            snapshotService.delete(fresh.instanceDirName, snapshot.id)
                        } catch (re: Throwable) {
                            // Apply failed AND the auto-rollback failed: keep the snapshot
                            // for a manual restore and surface both errors, don't mask the
                            // original apply failure with the restore one.
                            e.addSuppressed(re)
                        }
                        journal.complete(fresh.instanceDirName)
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

    private suspend fun previewFor(instance: PackInstance, target: SmrtPackManifest): UpdateCheck {
        val targetManifest = target.toBaselineManifest()
        val paths = instance.installedManifest?.flatten()?.keys.orEmpty() + targetManifest.flatten().keys
        val plan = computePlan(instance, target, targetManifest, scanInstanceState(clientDirOf(instance), paths))
        return UpdateCheck.Available(currentVersionOf(instance), target.packVersion, gradeCompat(instance, target), plan)
    }

    /**
     * The update plan: mods matched by identity, assets by path. A mod carries
     * its version in the filename, so one that renames across a bump (JEI.jar ->
     * jei.jar) must be tracked by [SmrtModEntry.stableKey] -- a path diff would
     * duplicate an edited jar (see [reconcileMods]). Baseline identity lives only
     * in the installed version's manifest, fetched via [fetchBaselineMods]; when
     * that is unavailable the plan falls back to the path-keyed whole-manifest
     * diff, i.e. the prior behaviour.
     */
    private suspend fun computePlan(
        instance: PackInstance,
        target: SmrtPackManifest,
        targetManifest: FileManifest,
        current: FileManifest,
    ): UpdatePlan {
        val baselineMods = fetchBaselineMods(instance)
            ?: return UpdateReconciler.reconcile(
                baseline = instance.installedManifest,
                target = targetManifest,
                current = current,
                isProtected = protectedPaths::isProtected,
            )
        val modPlan = reconcileMods(baselineMods, target.mods, current, protectedPaths::isProtected)
        val assetPlan = UpdateReconciler.reconcile(
            baseline = instance.installedManifest?.let(::assetsOnly),
            target = assetsOnly(targetManifest),
            current = current,
            isProtected = protectedPaths::isProtected,
        )
        return modPlan.mergedWith(assetPlan)
    }

    /**
     * The installed version's mods, which carry the identity `installedManifest`
     * dropped when it collapsed each mod to `mods/<filename>`. Null (caller falls
     * back to the path reconcile) when there is no baseline, no known current
     * version, or the mirror fetch fails (offline, or a retired build) -- so an
     * update degrades to the prior behaviour rather than erroring.
     */
    private suspend fun fetchBaselineMods(instance: PackInstance): List<SmrtModEntry>? {
        if (instance.installedManifest == null) return null
        val version = currentVersionOf(instance) ?: return null
        return runCatching { client.fetchManifestVersion(instance.packRef.id, version).mods }
            .onFailure {
                log.warn(
                    "update: baseline fetch failed for {} {}; path-reconcile fallback: {}",
                    instance.packRef.id, version, it.toString(),
                )
            }
            .getOrNull()
    }

    private fun assetsOnly(manifest: FileManifest): FileManifest =
        fileManifestOf(manifest.flatten().filterKeys { !it.startsWith("mods/") })

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

    /**
     * The mirror's retained builds for [instance], newest first. Server order is
     * kept as-is: publish date is the only ranking that holds across channels,
     * and the listing already arrives sorted by it.
     */
    override suspend fun availableBuilds(instance: PackInstance): List<SmrtManifestBuild> = withContext(Dispatchers.IO) {
        client.listBuilds(instance.packRef.id).builds
    }

    /** Snapshots [instance] can be rolled back to, newest first. */
    override fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
        snapshotService.list(instance.instanceDirName)

    /**
     * Roll [instance] back to snapshot [snapshotId]: restore the captured files
     * and the pre-update instance record under the mutation lock. Returns the
     * restored instance.
     */
    override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
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
