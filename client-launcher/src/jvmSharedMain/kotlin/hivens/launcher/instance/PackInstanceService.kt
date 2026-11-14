package hivens.launcher.instance

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.io.InstanceMutationLock
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.catalogue.PackArt
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackReattacher
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import hivens.launcher.update.ReattachPreviewStaleException
import hivens.launcher.update.reattachArchiveDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Instance-level mutations that reach past the registry: a full delete (files on
 * disk THEN the registry entry), a detach-to-Local fork and the reattach that
 * undoes it. Plain field edits (rename, notes, runtime) stay a direct
 * [IPackRepository.put] at the call site; these live here because the delete's fs
 * half must be ordered against the registry, and the delete and detach were
 * previously duplicated inline across the Library and detail surfaces.
 */
class PackInstanceService(
    private val repository: IPackRepository,
    private val dataDir: Path,
    private val running: RunningPackSource,
    private val work: InstanceWorkRegistry,
    private val snapshots: PackSnapshotService,
    private val journal: ApplyJournal,
    private val sizes: InstanceSizeService,
    /**
     * The cover already known for a pack, with no network: what it captured and
     * what its source has answered this run. A detach writes it into the record.
     */
    private val knownArt: (PackInstance) -> PackArt? = { null },
    /** What can put a detached pack back on its source, by the source's origin. */
    private val reattachers: Map<PackOrigin, PackReattacher> = emptyMap(),
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
        if (running.runningPackInstanceId.value == instance.id) return@withContext DeleteOutcome.GameRunning
        // Checked and marked as one step: a catalogue install claiming the pack between
        // a look and a mark went on writing into a tree being removed.
        val mark = when (val c = work.claim(instance.id, InstanceWork.Delete)) {
            is InstanceWorkRegistry.Claim.Held -> c.mark
            is InstanceWorkRegistry.Claim.Taken -> return@withContext DeleteOutcome.Busy(c.by)
        }
        try {
            if (running.runningPackInstanceId.value == instance.id) return@withContext DeleteOutcome.GameRunning
            val dir = instanceDirOf(instance)
            InstanceMutationLock.withLock(dir) {
                if (deleteTree(dir)) {
                    repository.delete(instance.id)
                    // What was kept about the instance outside its directory goes with
                    // it. Left behind, up to three snapshots of its files stayed on disk
                    // for good, since the prune runs only inside an apply.
                    snapshots.deleteAll(instance.instanceDirName)
                    journal.complete(instance.instanceDirName)
                    // The source archive a reattach preview kept, which nothing else would remove.
                    val _ = deleteTree(reattachArchiveDir(dataDir, instance.instanceDirName))
                    sizes.forget(instance.id)
                    DeleteOutcome.Deleted
                } else {
                    DeleteOutcome.Incomplete
                }
            }
        } finally {
            mark.release()
        }
    }

    /**
     * Fork this instance into a Local one the user owns: flip origin to Local and
     * record where it came from in [PackInstance.forkedFrom] so provenance (and
     * its art) survive. Moves no files -- the same on-disk instance is now Local.
     * [reattach] undoes it.
     *
     * The server binding goes too. Flipping only the origin left the cached
     * manifest's auth requirement in place, so the launch still treated the pack as
     * bound and swept `mods/` against the mirror's baseline, while the Content tab
     * opened adding and removing mods because the origin was now Local: whatever
     * the player added was deleted at Play. A pack whose contents are its owner's
     * is not one a server session can be lent to, so it launches unbound.
     *
     * The cover known at this moment is written into the record. Art is looked up
     * from the pack's source, and an install that captured none left a detached
     * pack with nothing to show offline, or before its source had been asked.
     */
    suspend fun detachToLocal(instance: PackInstance): PackInstance {
        // Held as an update holds the instance, and under the same lock. A detach that
        // landed while an update ran had the update's commit put the source back, or
        // its rollback re-bind the pack, with the player's own mods in it.
        val mark = when (val claim = work.claim(instance.id, InstanceWork.Update)) {
            is InstanceWorkRegistry.Claim.Held -> claim.mark
            is InstanceWorkRegistry.Claim.Taken -> throw InstanceBusyException(instance.id, claim.by)
        }
        try {
            // Not waited for: a sync that holds the files without a claim would keep
            // this mark up, and Play refused as "updating", for as long as it ran.
            return InstanceMutationLock.tryWithLock(instanceDirOf(instance)) { detachRecord(instance) }
                ?: throw InstanceBusyException(instance.id, InstanceWork.Update)
        } finally {
            mark.release()
        }
    }

    private suspend fun detachRecord(instance: PackInstance): PackInstance {
        val art = knownArt(instance)
        val detach: (PackInstance) -> PackInstance = { current ->
            current.copy(
                packRef = current.packRef.copy(origin = PackOrigin.Local),
                forkedFrom = current.forkedFrom ?: current.packRef,
                cachedManifest = current.cachedManifest?.copy(authRequirement = null),
                iconUrl = art?.iconUrl ?: current.iconUrl,
                bannerUrl = art?.bannerUrl ?: current.bannerUrl,
            )
        }
        return repository.update(instance.id, detach) ?: detach(instance)
    }

    /**
     * Whether a detach of [instance] could be undone: its source is one that can
     * take a detached pack back. A pack synced from a game server cannot, so its
     * detach is for good, and says so.
     */
    fun canReattachAfterDetach(instance: PackInstance): Boolean = instance.packRef.origin in reattachers

    /**
     * Whether [instance] was detached from a source that can take it back. A pack
     * that was never detached, or came from a source with no builds to return to,
     * has nothing to reattach to.
     */
    fun canReattach(instance: PackInstance): Boolean = reattacherFor(instance) != null

    /**
     * What reattaching [instance] would remove and bring back, against the source
     * build it would land on. Throws when [canReattach] is false.
     */
    suspend fun previewReattach(instance: PackInstance): ReattachPreview {
        val (source, reattacher) = requireReattacher(instance)
        return reattacher.previewReattach(instance, source)
    }

    /**
     * Puts [instance] back on the source it was detached from: its files brought to
     * the source's build, the source restored as its reference, the fork note
     * cleared and the server binding written back the way an install of that build
     * writes it. Throws when [canReattach] is false.
     *
     * Runs only on a preview the player confirmed: [confirmed] names the build it
     * measured and what it listed, the source measures the pack again before it
     * writes anything, and a pack that no longer measures the same is refused with
     * [ReattachPreviewStaleException] and left as it was. There is no way to reach
     * the files without having been shown what happens to them.
     *
     * The files move the way the source's ordinary update moves them, so a config
     * edit the build did not change stays. Local changes to the mods are not kept:
     * `mods/` is the build's again, so what the player added goes and what they
     * removed or changed comes back. A pack bound to a server may not carry mods of
     * its player's choosing, and a player who wants their own mods publishes their
     * fork as a pack of its own.
     */
    suspend fun reattach(
        instance: PackInstance,
        confirmed: ReattachConfirmation,
        progress: ((current: Int, total: Int, path: String) -> Unit)? = null,
    ): PackInstance {
        val (source, reattacher) = requireReattacher(instance)
        // The files are rewritten under the game otherwise. The surfaces that offer
        // this ask first, and a command arriving from elsewhere is refused here.
        check(running.runningPackInstanceId.value != instance.id) { "the game of pack ${instance.id} is running" }
        return reattacher.reattach(instance, source, confirmed, progress)
    }

    /** Lets go of what [instance]'s reattach previews kept, see [PackReattacher.discardPreview]. */
    suspend fun discardReattachPreview(instance: PackInstance) {
        reattacherFor(instance)?.second?.discardPreview(instance)
    }

    /**
     * What to call the source [instance] was detached from, for a player to read:
     * the name the source publishes, else the pack's own name, else the source's id.
     * Null when it cannot be reattached at all.
     */
    suspend fun reattachSourceName(instance: PackInstance): String? {
        val (source, reattacher) = reattacherFor(instance) ?: return null
        return reattacher.sourceName(source) ?: instance.displayName.takeIf { it.isNotBlank() } ?: source.id
    }

    private fun reattacherFor(instance: PackInstance): Pair<PackReference, PackReattacher>? {
        val source = instance.forkedFrom ?: return null
        return reattachers[source.origin]?.let { source to it }
    }

    private fun requireReattacher(instance: PackInstance): Pair<PackReference, PackReattacher> =
        reattacherFor(instance) ?: throw IllegalStateException("pack ${instance.id} was not detached from a source that can take it back")

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

/** Other work holds the instance [instanceId], so nothing was done to it. */
class InstanceBusyException(instanceId: String, val work: InstanceWork) :
    IllegalStateException("pack $instanceId is busy: $work")
