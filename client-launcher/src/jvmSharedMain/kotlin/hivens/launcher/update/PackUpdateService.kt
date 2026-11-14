package hivens.launcher.update

import hivens.core.update.PackBuild
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.toBaselineManifest
import hivens.core.api.dto.smrt.toDomain
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.FileManifest
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackReference
import hivens.core.data.fileManifestOf
import hivens.core.data.flatten
import hivens.core.io.InstanceMutationLock
import hivens.core.net.RepairReport
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateDirection
import hivens.core.update.UpdateOutcome
import hivens.core.update.UpdatePlan
import hivens.core.update.CompatChange
import hivens.core.update.UpdateReconciler
import hivens.core.update.classifyCompat
import hivens.core.update.mergedWith
import hivens.core.update.reconcileMods
import hivens.launcher.record.withBuildOf
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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
    private val snapshotService: PackSnapshotService,
    private val journal: ApplyJournal,
    private val dataDir: Path,
    /** The reader's language tag, for the release notes the mirror writes per language. */
    private val language: () -> String = { "" },
) : PackUpdater, PackReattacher {
    private val log = LoggerFactory.getLogger(PackUpdateService::class.java)
    private val guard = ApplyGuard(snapshotService, journal, repository)

    private fun clientDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    private fun currentVersionOf(instance: PackInstance): String? =
        instance.pinnedPackVersion ?: instance.packRef.version

    /** Every mirror build has a manifest, so any two of them can be compared unread. */
    override fun describesBuildContents(instance: PackInstance): Boolean = true

    /**
     * Read-only preview: is a different build current on the mirror, and what
     * would applying it do? Detection is label INEQUALITY, not tuple ordering:
     * versions are canonical strings, channel builds (`SNAPSHOT-...`) do not
     * tuple-compare against releases, and a mirror-side rollback of latest is
     * also a change to surface. Advisory -- it scans disk without the mutation
     * lock; the authoritative plan is recomputed under the lock in [applyUpdate].
     */
    override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck = withContext(Dispatchers.IO) {
        val packId = instance.packRef.id
        // ONE read answers both "did it move" and "to what". Asking the summary
        // for the latest label and the manifest endpoint for the plan means two
        // cache namespaces with two TTLs, which drift apart and let the banner
        // name a build the decision was not made against.
        val target = client.fetchManifest(packId, forceRefresh)
        if (currentVersionOf(instance) == target.packVersion) {
            return@withContext UpdateCheck.UpToDate
        }
        previewFor(instance, target)
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
     * and carried-over optional-toggle set. Throws [InstanceRemovedException] when
     * the instance was deleted while this waited for the lock.
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
                val fresh = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                requireSameProvenance(instance, fresh)
                val targetManifest = target.toBaselineManifest()
                val paths = fresh.installedManifest?.flatten()?.keys.orEmpty() + targetManifest.flatten().keys
                val plan = computePlan(fresh, target, targetManifest, scanInstanceState(clientDir, paths), fetchBaselineMods(fresh))
                val compat = gradeCompat(fresh, target)
                val enabledState = OptionalContentRules.enabledState(target.mods, fresh.optionalContent)
                val assetState = OptionalContentRules.assetState(target.assets, fresh.optionalContent, OptionalContentRules.placedIn(clientDir))
                val commitBuild: suspend () -> Unit = {
                    syncService.applyUpdate(clientDir, target, plan, enabledState, progress, assetState)
                    commit(fresh, target, pinExplicit = targetVersion != null)
                }
                // A label-only move writes no file, so there is nothing to snapshot
                // or roll back and it commits bare.
                if (plan.isEmpty) {
                    commitBuild()
                } else {
                    val managed = managedRealPaths(fresh.installedManifest, targetManifest)
                    guard.apply(clientDir, fresh, managed, currentVersionOf(fresh), target.packVersion, rewrite = commitBuild)
                }
                // After the commit and outside it: the update is done by here, and a
                // cache that failed to clear is no reason to undo it. Left alone, the
                // next check answers from a cache older than this apply and reports
                // an update back to the build we came from.
                runCatching { client.invalidatePack(packId) }
                    .onFailure { if (it is CancellationException) throw it else log.warn("update: could not drop the cached views of {}", packId, it) }
                log.info(
                    "update: pack={} {} -> {} ({} add, {} update, {} delete, {} conflict, compat={})",
                    packId, currentVersionOf(fresh), target.packVersion,
                    plan.toAdd.size, plan.toUpdate.size, plan.toDelete.size, plan.conflicts.size, compat,
                )
                UpdateOutcome.Applied(target.packVersion, compat, plan)
            }
        }
    }

    /**
     * Verify an installed instance against the build it is pinned to, and put right
     * whatever does not match. Returns what was checked, what was wrong, and what
     * the repair actually cost in bytes.
     *
     * The manifest comes from the PINNED version, not the mirror's latest: a repair
     * restores the pack the user has, and measuring it against a newer build would
     * report every changed file as damage and then "fix" it into an update nobody
     * asked for.
     */
    suspend fun verifyAndRepair(
        instance: PackInstance,
        progress: ((current: Int, total: Int, path: String) -> Unit)? = null,
    ): RepairReport = withContext(Dispatchers.IO) {
        val packId = instance.packRef.id
        val version = currentVersionOf(instance)
        val manifest = if (version != null) {
            client.fetchManifestVersion(packId, version)
        } else {
            client.fetchManifest(packId)
        }
        val enabledState = OptionalContentRules.enabledState(manifest.mods, instance.optionalContent)
        val assetState = OptionalContentRules.assetState(manifest.assets, instance.optionalContent, OptionalContentRules.placedIn(clientDirOf(instance)))
        val report = syncService.verifyAndRepair(clientDirOf(instance), manifest, enabledState, assetState, progress)
        log.info(
            "repair: pack={} version={} checked={} intact={} repaired={} fetched={}B failed={}",
            packId, version, report.checked, report.intact, report.repaired.size, report.bytesFetched, report.failed.size,
        )
        report
    }

    private suspend fun previewFor(instance: PackInstance, target: SmrtPackManifest): UpdateCheck {
        val targetManifest = target.toBaselineManifest()
        val paths = instance.installedManifest?.flatten()?.keys.orEmpty() + targetManifest.flatten().keys
        val plan = computePlan(instance, target, targetManifest, scanInstanceState(clientDirOf(instance), paths), fetchBaselineMods(instance))
        val from = currentVersionOf(instance)
        return UpdateCheck.Available(
            fromVersion = from,
            toVersion = target.packVersion,
            direction = directionOf(instance.packRef.id, from, target.packVersion),
            compat = gradeCompat(instance, target),
            plan = plan,
        )
    }

    /**
     * Ranks the move by the mirror's publish order, which is the listing's own
     * order and the only ranking that holds across channels. Degrades to
     * [UpdateDirection.Unknown] rather than guessing: with no baseline, with a
     * build the listing no longer retains, or offline, an unranked move is
     * honest where a tuple comparison would be confidently wrong.
     */
    private suspend fun directionOf(packId: String, from: String?, to: String): UpdateDirection {
        if (from == null) return UpdateDirection.Unknown
        val order = runCatching { client.listBuilds(packId).builds.map { it.versionNumber } }
            .onFailure { log.warn("update: build listing unavailable for {}; direction unranked: {}", packId, it.toString()) }
            .getOrNull()
            ?: return UpdateDirection.Unknown
        val fromIndex = order.indexOf(from)
        val toIndex = order.indexOf(to)
        if (fromIndex < 0 || toIndex < 0) return UpdateDirection.Unknown
        return when {
            toIndex < fromIndex -> UpdateDirection.Newer
            toIndex > fromIndex -> UpdateDirection.Older
            else -> UpdateDirection.Unknown
        }
    }

    /**
     * The update plan: mods matched by identity, assets by path. A mod carries
     * its version in the filename, so one that renames across a bump (JEI.jar ->
     * jei.jar) must be tracked by [SmrtModEntry.stableKey] -- a path diff would
     * duplicate an edited jar (see [reconcileMods]). Baseline identity lives only
     * in the installed version's manifest, fetched via [fetchBaselineMods]; when
     * that is unavailable the plan falls back to the path-keyed whole-manifest
     * diff, i.e. the prior behaviour.
     *
     * No protected-path list takes part. Here the reconcile has a baseline, so it
     * can tell an edit from a stale file by content and answer each path on the
     * evidence -- which is the thing a list of names was standing in for, and it
     * stands in badly: a name is not evidence, protection by name is lifted by a
     * rename, and the list is a file the instance's own owner can extend.
     *
     * [baselineMods] is what [fetchBaselineMods] answers, handed in so a caller that
     * has to know which reading the plan got asks only once.
     */
    private fun computePlan(
        instance: PackInstance,
        target: SmrtPackManifest,
        targetManifest: FileManifest,
        current: FileManifest,
        baselineMods: List<SmrtModEntry>?,
    ): UpdatePlan {
        if (baselineMods == null) {
            return UpdateReconciler.reconcile(
                baseline = instance.installedManifest,
                target = targetManifest,
                current = current,
            )
        }
        val modPlan = reconcileMods(baselineMods, target.mods, current)
        val assetPlan = UpdateReconciler.reconcile(
            baseline = instance.installedManifest?.let(::assetsOnly),
            target = assetsOnly(targetManifest),
            current = current,
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
     *
     * [reattachTo] puts a detached pack back on that source in the same write, so
     * the reference, the server binding and the build it rests on land together.
     */
    private suspend fun commit(
        instance: PackInstance,
        target: SmrtPackManifest,
        pinExplicit: Boolean,
        reattachTo: PackReference? = null,
    ): PackInstance? =
        // Onto the record as it is at the commit, not as it was read when the update
        // began: an update takes minutes, and a game exiting meanwhile records its
        // playtime, which a whole-record write put back to zero.
        repository.update(instance.id) { record ->
            val current = reattachTo?.let { record.backOn(it, target.packVersion) } ?: record
            current.copy(
                packRef = current.packRef.copy(version = target.packVersion),
                pinnedPackVersion = target.packVersion,
                followLatest = if (pinExplicit) false else current.followLatest,
                installedManifest = target.toBaselineManifest(),
                cachedManifest = CachedManifestSnapshot(
                    minecraftVersion = target.minecraft.version,
                    loaderName = target.loader.name,
                    loaderVersion = target.loader.version,
                    javaMajor = target.java.major,
                    authRequirement = target.auth?.toDomain(),
                ),
                // The choice as it stands now, carried onto the new build. The files
                // were placed from the choice read when the apply began, and a switch
                // made during it is relabelled once the lock is released.
                optionalContent = OptionalContentRules.carried(target, current.optionalContent, OptionalContentRules.placedIn(clientDirOf(current))),
            )
        }

    /**
     * See [PackReattacher.previewReattach]. Measured the way the reattach moves the
     * pack: the plan an ordinary update to that build would make, with `mods/` held
     * strictly to the build, every mod moved between its own name and its
     * switched-off one, and what the sweep removes.
     */
    override suspend fun previewReattach(instance: PackInstance, source: PackReference): ReattachPreview =
        withContext(Dispatchers.IO) {
            reattachPlan(clientDirOf(instance), instance, reattachTarget(instance, source)).preview
        }

    /**
     * See [PackReattacher.reattach]. The build goes on through the apply an ordinary
     * update runs, from the plan the preview showed, then the mod folders and stray
     * archives the build does not ship are removed, and the record is written by the
     * same commit an update makes. A failure part way is rolled back to the files
     * and record the pack had before.
     */
    override suspend fun reattach(
        instance: PackInstance,
        source: PackReference,
        confirmed: ReattachConfirmation,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): PackInstance {
        val target = client.fetchManifestVersion(source.id, confirmed.build)
        val clientDir = clientDirOf(instance)
        val written = InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val fresh = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                // Measured again under the lock, so what is written is what the
                // player read, or nothing is.
                val measured = reattachPlan(clientDir, fresh, target)
                measured.preview.requireConfirmed(confirmed, instance.id)
                // What the sweep takes is captured too, folders file by file, so a
                // reattach that fails part way puts the player's mods back with
                // everything else.
                val managed = managedRealPaths(measured.baseline, target.toBaselineManifest()) +
                    filesUnder(clientDir, measured.preview.removed)
                val enabledState = OptionalContentRules.enabledState(target.mods, fresh.optionalContent)
                val assetState = OptionalContentRules.assetState(target.assets, fresh.optionalContent, OptionalContentRules.placedIn(clientDir))
                guard.apply(clientDir, fresh, managed, currentVersionOf(fresh), target.packVersion, reattach = true) {
                    // The download is checked against the file in place before it is
                    // fetched, and through a link that check reads the link's target.
                    measured.links.forEach { Files.deleteIfExists(clientDir.resolve(it)) }
                    syncService.applyUpdate(clientDir, target, measured.plan, enabledState, progress, assetState)
                    val drop = syncService.dropStrays(clientDir, measured.strays)
                    if (drop.blocked.isNotEmpty()) throw IOException("could not remove ${drop.blocked.joinToString()} from mods/")
                    commit(fresh, target, pinExplicit = false, reattachTo = source)
                } ?: throw InstanceRemovedException(instance)
            }
        }
        log.info("reattach: {} back on {} {}", instance.instanceDirName, source.id, target.packVersion)
        return written
    }

    override suspend fun sourceName(source: PackReference): String? =
        runCatching { client.fetchSummary(source.id).displayName }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * The build a reattach would land on: the pinned build where the pack is pinned
     * and the mirror still has it, and the mirror's latest where it is not.
     */
    private suspend fun reattachTarget(instance: PackInstance, source: PackReference): SmrtPackManifest {
        val pin = instance.reattachPin { pin -> client.listBuilds(source.id).builds.any { it.versionNumber == pin } }
        return if (pin != null) client.fetchManifestVersion(source.id, pin) else client.fetchManifest(source.id)
    }

    /**
     * A reattach measured: what it shows, the plan it applies, the baseline that plan
     * was made against, and the strays it removes, as measured.
     */
    private class ReattachPlan(
        val preview: ReattachPreview,
        val plan: UpdatePlan,
        val baseline: FileManifest?,
        val strays: List<String>,
        /** Mod paths that are links, which the build's own file replaces: the link goes first. */
        val links: List<String>,
    )

    /**
     * The plan an ordinary update of [record] to [target] would make, with `mods/`
     * held strictly to the build.
     *
     * The baseline is the one the record kept: a detach leaves the installed build's
     * baseline in place, so the reconcile tells the player's edits from the pack's
     * as an update does. A record from before baselines existed has none, and the
     * manifest of the build it was detached from stands in, which is the same thing
     * written down later. Without either, every difference is the pack's to write,
     * as an update with no baseline treats it, and the preview says so.
     */
    private suspend fun reattachPlan(clientDir: Path, record: PackInstance, target: SmrtPackManifest): ReattachPlan {
        val baseline = record.installedManifest ?: currentVersionOf(record)?.let { version ->
            runCatching { client.fetchManifestVersion(record.packRef.id, version).toBaselineManifest() }
                .onFailure { if (it is CancellationException) throw it else log.warn("reattach: no baseline for {}: {}", record.instanceDirName, it.toString()) }
                .getOrNull()
        }
        val based = record.copy(installedManifest = baseline)
        val targetManifest = target.toBaselineManifest()
        val current = scanInstanceState(clientDir, baseline?.flatten()?.keys.orEmpty() + targetManifest.flatten().keys)
        val baselineMods = fetchBaselineMods(based)
        val basis = when {
            baseline == null -> ReattachBasis.NoBaseline
            baselineMods == null -> ReattachBasis.NamesOnly
            else -> ReattachBasis.Full
        }
        // A link under a mod's name is not the build's file whatever it points at
        // today, so it is written over like any other difference. The scan reads
        // through it and would call it the build's.
        val links = target.mods.flatMap { mod -> listOf("mods/${mod.filename}", "mods/${mod.filename}$DISABLED") }
            .filter { Files.isSymbolicLink(clientDir.resolve(it)) }
        val plan = computePlan(based, target, targetManifest, current, baselineMods)
            .strictInMods(target, current, links.map { it.removeSuffix(DISABLED) }.toSet())

        val touched = plan.toAdd.toSet() + plan.toUpdate + plan.toDelete + plan.conflicts
        val turnedOn = ArrayList<String>()
        val turnedOff = ArrayList<String>()
        // A file with the right bytes under the other name is no difference to the
        // scan, which reads either name, and the apply's relabel still moves it.
        // Asked the way the relabel asks it, from the same choice.
        fun sortByName(path: String, on: Boolean) {
            if (path in touched) return
            val active = Files.isRegularFile(clientDir.resolve(path))
            val off = Files.isRegularFile(clientDir.resolve("$path$DISABLED"))
            when {
                on && !active && off -> turnedOn += path
                !on && active && !off -> turnedOff += path
            }
        }
        val enabledState = OptionalContentRules.enabledState(target.mods, record.optionalContent)
        target.mods.forEach { mod -> sortByName("mods/${mod.filename}", enabledState[mod.filename] ?: (mod.required || mod.defaultEnabled)) }
        val assetState = OptionalContentRules.assetState(target.assets, record.optionalContent, OptionalContentRules.placedIn(clientDir))
        target.assets.filter { it.hasOffName }.forEach { asset ->
            sortByName(asset.dest, !asset.toggleable || (assetState[asset.dest] ?: true))
        }
        val scan = syncService.strays(clientDir, target)
        val strays = scan.strays
        val preview = ReattachPreview(
            build = target.packVersion,
            toVersion = target.packVersion,
            compat = gradeCompat(based, target),
            removed = (plan.toDelete + strays).distinct().sorted(),
            restored = plan.toAdd.sorted(),
            replaced = plan.toUpdate.sorted(),
            turnedOn = turnedOn.sorted(),
            turnedOff = turnedOff.sorted(),
            kept = plan.conflicts.sorted(),
            basis = basis,
            unreadable = scan.unreadable,
        )
        return ReattachPlan(preview, plan, baseline, strays, links)
    }

    /**
     * [this] plan with `mods/` held to the build, whatever the reconcile made of it.
     *
     * Asked of the disk directly rather than of the plan's lists: a reconcile keeps a
     * jar the player changed where the build did not change it, and with no identity
     * to go on it keeps one the build did change too, and neither lands in a list
     * this could correct. So every mod the build ships is written unless the disk
     * already holds its bytes, under either of its names, and one that does is not
     * listed at all, since nothing is written for it, unless that is a link: [linked]
     * mods are written over, see [reattachPlan].
     */
    private fun UpdatePlan.strictInMods(target: SmrtPackManifest, current: FileManifest, linked: Set<String>): UpdatePlan {
        val onDisk = current.flatten()
        val add = LinkedHashSet(toAdd)
        val update = LinkedHashSet(toUpdate)
        val parked = conflicts.toMutableSet()
        for (mod in target.mods) {
            val path = "mods/${mod.filename}"
            val have = onDisk[path]?.sha1
            add -= path
            update -= path
            parked -= path
            when {
                have == null -> add += path
                path in linked || !have.equals(mod.sha1, ignoreCase = true) -> update += path
            }
        }
        return copy(toAdd = add.toList(), toUpdate = update.toList(), conflicts = conflicts.filter { it in parked })
    }

    /**
     * The mirror's retained builds for [instance], newest first. Server order is
     * kept as-is: publish date is the only ranking that holds across channels,
     * and the listing already arrives sorted by it.
     */
    override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> = withContext(Dispatchers.IO) {
        val tag = language()
        client.listBuilds(instance.packRef.id).builds.map { it.forLanguage(tag) }
    }

    /** The same listing, stale-then-fresh, for a screen that must not miss a build the cache predates. */
    override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> =
        client.buildsStream(instance.packRef.id)
            .map { listing ->
                val tag = language()
                listing.builds.map { it.forLanguage(tag) }
            }
            .flowOn(Dispatchers.IO)

    /** Snapshots [instance] can be rolled back to, newest first. */
    override fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
        snapshotService.list(instance.instanceDirName)

    /**
     * Roll [instance] back to snapshot [snapshotId]: restore the captured files
     * and the pre-update build onto the record under the mutation lock. Returns the
     * record as written.
     */
    override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
        val clientDir = clientDirOf(instance)
        val rolled = InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                val managed = managedRealPaths(null, current.installedManifest ?: FileManifest())
                val reattach = snapshotService.takenByReattach(current.instanceDirName, snapshotId)
                val restored = snapshotService.restore(clientDir, current.instanceDirName, snapshotId, managed)
                // A rollback is a deliberate pin: stop following latest so the update we
                // just undid is not re-applied on the next startup.
                val written = repository.update(current.id) { it.withBuildOf(restored, provenance = reattach).copy(followLatest = false) }
                    ?: current.withBuildOf(restored, provenance = reattach).copy(followLatest = false)
                written to reattach
            }
        }
        // A reattach undone is a Local pack again, whose mods are its owner's: the
        // build's rules for which of them are switched on are no longer its rules.
        if (!rolled.second) relabelAfterRollback(clientDir, rolled.first)
        return rolled.first
    }

    /**
     * Puts the restored files under the names the record's choice gives them.
     *
     * A snapshot gives each file back under the name it had when it was taken, and
     * the record keeps the choice as it is now, so a mod or a resource pack switched
     * since came back under its old name: on in the record and off in the game, or
     * the other way round. Which files are switchable at all is the restored build's
     * manifest's to say, so without it (offline, a build the mirror retired) the
     * names stay as restored and the next sync or update relabels them.
     */
    private suspend fun relabelAfterRollback(clientDir: Path, instance: PackInstance) {
        val version = currentVersionOf(instance) ?: return
        val manifest = try {
            client.fetchManifestVersion(instance.packRef.id, version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("rollback: the manifest of {} {} is unavailable, names left as restored: {}", instance.packRef.id, version, e.toString())
            return
        }
        InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                syncService.relabel(clientDir, manifest.mods, OptionalContentRules.enabledState(manifest.mods, instance.optionalContent))
                syncService.relabelAssets(
                    clientDir,
                    manifest.assets,
                    OptionalContentRules.assetState(manifest.assets, instance.optionalContent, OptionalContentRules.placedIn(clientDir)),
                )
            }
        }
    }

}

/** The name a switched-off file sits under, beside its own. */
private const val DISABLED = ".disabled"
