package hivens.launcher.update

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackReference
import hivens.core.io.InstanceMutationLock
import hivens.core.io.deleteTree
import hivens.core.update.CompatChange
import hivens.core.update.PackBuild
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateDirection
import hivens.core.update.UpdateOutcome
import hivens.core.update.classifyCompat
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.mrpack.MrpackSource
import hivens.launcher.smrt.SmrtSyncService
import hivens.launcher.util.sha1Of
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import hivens.launcher.record.PackFileRecord
import hivens.launcher.record.withBuildOf

/**
 * Moves a Modrinth-installed instance between versions of its pack.
 *
 * The sibling of [PackUpdateService], and deliberately much thinner. The mirror
 * publishes every file of every build with a hash, so it can say what an update
 * would do before doing it. Modrinth publishes a version object and an archive,
 * so this can say WHICH build and WHAT it runs on, and nothing about files until
 * the archive is in hand. Everything downstream is built to accept that: the
 * plan on a check is nullable precisely so this does not have to invent one.
 *
 * Applying goes through [MrpackInstaller.update], which reconciles against the
 * record written at install: the pack's own files move, and whatever the player
 * added stays where they put it. It runs inside the same [ApplyGuard] as the
 * mirror path, so a failed or interrupted update is rolled back rather than left
 * half-converted.
 */
class ModrinthPackUpdater(
    private val client: ModrinthClient,
    private val installer: MrpackInstaller,
    private val repository: IPackRepository,
    private val snapshotService: PackSnapshotService,
    journal: ApplyJournal,
    private val dataDir: Path,
    /**
     * Whose rule says which files in `mods/` a build does not account for. Asked
     * only by a reattach, which hands `mods/` back to the build: the same rule the
     * mirror's sync sweeps by, so a reattach means the same whichever source the
     * pack came from.
     */
    private val roster: SmrtSyncService,
) : PackUpdater, PackReattacher {

    private val log = LoggerFactory.getLogger(ModrinthPackUpdater::class.java)
    private val guard = ApplyGuard(snapshotService, journal, repository)

    private fun clientDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    private fun currentVersionOf(instance: PackInstance): String? =
        instance.pinnedPackVersion ?: instance.packRef.version

    override fun handles(instance: PackInstance): Boolean = true

    /**
     * Newest first by publish date, ranked here rather than trusted from the
     * response: the order is not part of the contract, and a listing that
     * arrives the other way round would silently make "the newest" the oldest.
     */
    override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> = withContext(Dispatchers.IO) {
        client.listVersions(instance.packRef.id)
            .sortedByDescending { it.datePublished }
            .map { it.toPackBuild() }
    }

    /**
     * One emission. The mirror's stream exists because its listing is cached and
     * a stale read has to be replaced by a fresh one; there is no such cache here
     * yet, so pretending to be stale-then-fresh would be a lie in a Flow's shape.
     */
    override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> =
        flow { emit(availableBuilds(instance)) }.flowOn(Dispatchers.IO)

    override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck =
        withContext(Dispatchers.IO) {
            val newest = versionsNewestFirst(instance).firstOrNull() ?: return@withContext UpdateCheck.UpToDate
            if (currentVersionOf(instance) == newest.versionNumber) UpdateCheck.UpToDate
            else available(instance, newest, UpdateDirection.Newer)
        }

    override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck =
        withContext(Dispatchers.IO) {
            if (currentVersionOf(instance) == targetVersion) return@withContext UpdateCheck.UpToDate
            val ordered = versionsNewestFirst(instance)
            val target = ordered.resolve(targetVersion) ?: return@withContext UpdateCheck.UpToDate
            // Direction by the listing's own order, never by comparing version
            // strings: a pack numbering its builds by date, or by anything else
            // that does not tuple-compare, would rank backwards.
            val here = ordered.indexOfFirst { it.versionNumber == currentVersionOf(instance) }
            val there = ordered.indexOf(target)
            val direction = when {
                here < 0 -> UpdateDirection.Unknown
                there < here -> UpdateDirection.Newer
                else -> UpdateDirection.Older
            }
            available(instance, target, direction)
        }

    override suspend fun applyUpdate(
        instance: PackInstance,
        targetVersion: String?,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): UpdateOutcome {
        val ordered = versionsNewestFirst(instance)
        val target = if (targetVersion != null) {
            ordered.resolve(targetVersion)
                ?: throw IOException("Modrinth has no version '$targetVersion' of pack ${instance.packRef.id}")
        } else {
            ordered.firstOrNull() ?: throw IOException("Modrinth lists no versions of pack ${instance.packRef.id}")
        }
        if (currentVersionOf(instance) == target.versionNumber) return UpdateOutcome.AlreadyCurrent

        val clientDir = clientDirOf(instance)
        val compat = compatOf(instance, target)

        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                requireSameProvenance(instance, current)
                // A directory, so the archive path does not exist yet: downloadTo
                // skips a target that is already on disk, and createTempFile
                // creates one, so handing it a temp FILE downloaded nothing and
                // left an empty archive to be opened as a zip.
                val scratch = Files.createTempDirectory("mrpack-update-")
                val archive = scratch.resolve("pack.mrpack")
                try {
                    val record = PackFileRecord.read(clientDir)
                    // An instance from before records existed has no baseline, and
                    // without one an update cannot retire what the old version
                    // shipped: every pack renames its jars per version, so the
                    // instance ends up carrying two of each mod and the game will
                    // not start. The old version's own archive is the baseline, and
                    // fetching it happens once -- the update writes a record.
                    val installedArchive = if (record.isEmpty()) {
                        val installed = currentVersionOf(current)
                        val previous = ordered.firstOrNull { it.versionNumber == installed }
                            ?: throw IOException(
                                "cannot update ${instance.packRef.id}: it has no record of what the pack " +
                                    "installed, and version '$installed' is no longer listed to read one from. " +
                                    "Reinstall the pack to update it.",
                            )
                        scratch.resolve("installed.mrpack").also { previous.primaryFile().let { f -> client.downloadTo(f.url, it, f.hashes.sha1) } }
                    } else {
                        null
                    }
                    // Held to the published digest: the index inside is what pins
                    // every other file the update fetches.
                    target.primaryFile().let { f -> client.downloadTo(f.url, archive, f.hashes.sha1) }
                    // Say what went wrong here rather than let a zero-byte file
                    // reach the zip reader, which reports only "zip file is empty"
                    // and names neither the pack nor where the bytes should have
                    // come from.
                    val bytes = runCatching { Files.size(archive) }.getOrDefault(0L)
                    if (bytes == 0L) {
                        throw IOException(
                            "downloaded nothing for ${instance.packRef.id} ${target.versionNumber} " +
                                "from ${target.primaryFile().url}",
                        )
                    }
                    // Everything the update can create, replace or retire, known before
                    // it touches anything: what the old version placed, what the new
                    // one places, and the record that describes which is which.
                    val managed = record.keys +
                        installedArchive?.let { runCatching { installer.archivePaths(it) }.getOrDefault(emptySet()) }.orEmpty() +
                        installer.archivePaths(archive) +
                        PackFileRecord.FILE_NAME
                    guard.apply(clientDir, current, managed, currentVersionOf(current), target.versionNumber) {
                        installer.update(
                            instance = current,
                            mrpack = archive,
                            source = MrpackSource(current.packRef.origin, current.packRef.id, target.versionNumber, buildKey = target.id),
                            installedArchive = installedArchive,
                            progress = progress ?: { _, _, _ -> },
                        )
                        // A version the player picked is a pin, as on the mirror: left
                        // following the latest, the auto-updater undid a deliberate
                        // move to an older build, and a reattach of a fork kept on it
                        // went to the newest.
                        if (targetVersion != null) repository.update(current.id) { it.copy(followLatest = false) }
                    }
                } finally {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    val _ = runCatching {
                        Files.list(scratch).use { s -> s.forEach { Files.deleteIfExists(it) } }
                        Files.deleteIfExists(scratch)
                    }
                }
                log.info("modrinth update: {} -> {}", instance.instanceDirName, target.versionNumber)
                UpdateOutcome.Applied(target.versionNumber, compat, plan = null)
            }
        }
    }

    /**
     * Preview folders a preview is still fetching into or measuring from. A discard
     * leaves these alone: they belong to a preview that has not answered yet.
     */
    private val previewing: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    /**
     * See [PackReattacher.previewReattach]. Modrinth says what a build contains only
     * inside its archive, so the archive is fetched to answer, into a folder of this
     * preview's own. It is kept there for the reattach that follows, which takes it
     * over, and goes when the preview is discarded, when a later preview of the same
     * pack answers, when the instance is deleted, or at the next start. A preview that
     * fails or is cancelled takes its folder with it.
     */
    override suspend fun previewReattach(instance: PackInstance, source: PackReference): ReattachPreview =
        withContext(Dispatchers.IO) {
            val target = reattachTarget(instance, source)
            val home = reattachArchiveDir(dataDir, instance.instanceDirName)
            val dir = home.resolve("$PREVIEW_PREFIX${UUID.randomUUID()}")
            previewing.add(dir)
            try {
                val archive = fetchArchive(dir, target)
                val preview = measure(clientDirOf(instance), instance, archive, target).preview
                // This one answers for the pack now: earlier previews' archives go.
                previewFolders(home).filter { it != dir && it !in previewing }.forEach(::dropArchives)
                preview
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    dropArchives(dir)
                    dropIfEmpty(home)
                }
                throw e
            } finally {
                previewing.remove(dir)
            }
        }

    /** See [PackReattacher.discardPreview]: every finished preview's archive for the pack goes. */
    override suspend fun discardPreview(instance: PackInstance) = withContext(Dispatchers.IO) {
        val home = reattachArchiveDir(dataDir, instance.instanceDirName)
        previewFolders(home).filter { it !in previewing }.forEach(::dropArchives)
        dropIfEmpty(home)
    }

    /**
     * See [PackReattacher.reattach]. The build goes on through [MrpackInstaller.update]
     * as an ordinary update puts it on, retirements included, with `mods/` held to
     * the build, then everything in `mods/` the build does not ship is swept by the
     * mirror's own rule, mod folders included, all inside the same guard an update
     * runs in. The record takes the build the way an update writes it, and then the
     * source back.
     *
     * The archive is the reattach's own for its whole length: the one its preview
     * fetched is moved into a folder only this reattach uses, or fetched there, so a
     * preview that starts, fails or is discarded meanwhile cannot take it away.
     */
    override suspend fun reattach(
        instance: PackInstance,
        source: PackReference,
        confirmed: ReattachConfirmation,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): PackInstance {
        val target = client.listVersions(source.id).resolve(confirmed.build) ?: throw ReattachPreviewStaleException(instance.id)
        val clientDir = clientDirOf(instance)
        val home = reattachArchiveDir(dataDir, instance.instanceDirName)
        val dir = home.resolve("$APPLY_PREFIX${UUID.randomUUID()}")
        try {
            val archive = takeOver(home, dir, target) ?: fetchArchive(dir, target)
            return InstanceMutationLock.withLock(clientDir) {
                withContext(Dispatchers.IO) {
                    val current = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                    // Measured again under the lock, so what is written is what the
                    // player read, or nothing is.
                    val measured = measure(clientDir, current, archive, target)
                    measured.preview.requireConfirmed(confirmed, instance.id)
                    val managed = PackFileRecord.read(clientDir).keys + installer.archivePaths(archive) +
                        filesUnder(clientDir, measured.preview.removed) + measured.preview.turnedOn.map { "$it.disabled" } +
                        PackFileRecord.FILE_NAME
                    guard.apply(clientDir, current, managed, currentVersionOf(current), target.versionNumber, reattach = true) {
                        installer.update(
                            instance = current,
                            mrpack = archive,
                            source = MrpackSource(source.origin, source.id, target.versionNumber, buildKey = target.id),
                            progress = progress ?: { _, _, _ -> },
                            strictMods = true,
                        )
                        val drop = roster.dropStrays(clientDir, measured.strays)
                        if (drop.blocked.isNotEmpty()) throw IOException("could not remove ${drop.blocked.joinToString()} from mods/")
                        log.info("modrinth reattach: {} back on {} {}, {} stray(s) removed", instance.instanceDirName, source.id, target.versionNumber, drop.removed.size)
                        repository.update(current.id) { it.backOn(source, target.versionNumber) }
                            ?: throw InstanceRemovedException(instance)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                dropArchives(dir)
                dropIfEmpty(home)
            }
        }
    }

    override suspend fun sourceName(source: PackReference): String? =
        runCatching { client.resolveProject(source.id).title }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** A preview, and the strays it measured, which the reattach removes as measured. */
    private class Measured(val preview: ReattachPreview, val strays: List<String>)

    private fun measure(clientDir: Path, record: PackInstance, archive: Path, target: ModrinthVersion): Measured {
        val divergence = installer.divergence(archive, clientDir)
        // Held to every path the build places under `mods/`, its versioned folders
        // included: a jar the build itself puts in `mods/<mcversion>/` is its own.
        val scan = roster.strays(clientDir, divergence.mods, divergence.modPaths)
        val preview = ReattachPreview(
            build = target.id,
            toVersion = target.versionNumber,
            compat = compatOf(record, target),
            removed = (divergence.retired + scan.strays).distinct().sorted(),
            restored = divergence.missing,
            replaced = divergence.changed,
            turnedOn = divergence.turnedOn,
            // No record means no telling the player's files from the pack's: every
            // file the version ships is written, edits included.
            basis = if (PackFileRecord.read(clientDir).isEmpty()) ReattachBasis.NoBaseline else ReattachBasis.Full,
            unreadable = scan.unreadable,
        )
        return Measured(preview, scan.strays)
    }

    /**
     * The version a reattach would land on: the pinned build where the pack is
     * pinned and Modrinth still lists it, and the newest where it is not.
     */
    private suspend fun reattachTarget(instance: PackInstance, source: PackReference): ModrinthVersion {
        val ordered = client.listVersions(source.id).sortedByDescending { it.datePublished }
        val pin = instance.reattachPin { pin -> ordered.resolve(pin) != null }
        return pin?.let { ordered.resolve(it) }
            ?: ordered.firstOrNull()
            ?: throw IOException("Modrinth lists no versions of pack ${source.id}")
    }

    /**
     * Moves a finished preview's archive of [target] into [dir], when one holds the
     * published bytes, and answers with it there. Moved, not copied, in one step: a
     * preview cleanup that runs at the same moment either finds it gone or loses it
     * before the move, and then the reattach fetches its own.
     */
    private suspend fun takeOver(home: Path, dir: Path, target: ModrinthVersion): Path? = withContext(Dispatchers.IO) {
        val name = archiveName(target)
        val sha1 = target.primaryFile().hashes.sha1
        Files.createDirectories(dir)
        for (folder in previewFolders(home).filter { it !in previewing }) {
            val kept = folder.resolve(name)
            if (!Files.isRegularFile(kept) || !sha1.equals(runCatching { sha1Of(kept) }.getOrNull(), ignoreCase = true)) continue
            val mine = dir.resolve(name)
            val moved = runCatching { Files.move(kept, mine, StandardCopyOption.ATOMIC_MOVE) }.isSuccess
            if (moved) {
                dropIfEmpty(folder)
                return@withContext mine
            }
        }
        null
    }

    /** [target]'s archive fetched into [dir], held to its published digest. */
    private suspend fun fetchArchive(dir: Path, target: ModrinthVersion): Path {
        val file = target.primaryFile()
        val archive = dir.resolve(archiveName(target))
        withContext(Dispatchers.IO) { Files.createDirectories(dir) }
        client.downloadTo(file.url, archive, file.hashes.sha1)
        // Say what went wrong here rather than let a zero-byte file reach the zip
        // reader, which names neither the pack nor where the bytes came from.
        if (withContext(Dispatchers.IO) { runCatching { Files.size(archive) }.getOrDefault(0L) } == 0L) {
            throw IOException("downloaded nothing for ${target.projectId} ${target.versionNumber} from ${file.url}")
        }
        return archive
    }

    private fun archiveName(target: ModrinthVersion): String = "${target.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.mrpack"

    private fun previewFolders(home: Path): List<Path> =
        if (!Files.isDirectory(home)) emptyList()
        else Files.list(home).use { entries -> entries.filter { it.fileName.toString().startsWith(PREVIEW_PREFIX) }.toList() }

    /** Removes [dir] when nothing is left in it. Something still in it keeps it. */
    private fun dropIfEmpty(dir: Path) {
        val _ = runCatching { Files.deleteIfExists(dir) }
    }

    private fun dropArchives(dir: Path) {
        runCatching { deleteTree(dir, clearReadOnly = false) }
            .onFailure { log.warn("modrinth reattach: could not remove the archive kept in {}", dir, it) }
    }

    override fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
        snapshotService.list(instance.instanceDirName)

    override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
        val clientDir = clientDirOf(instance)
        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: throw InstanceRemovedException(instance)
                // The record goes with the files. A snapshot that holds it puts back
                // the one describing the restored build, and one taken before records
                // were captured drops the current one, which describes the build being
                // undone: no record is read as "retire nothing", a wrong one as
                // "retire the wrong things".
                val managed = PackFileRecord.read(clientDir).keys + PackFileRecord.FILE_NAME
                val reattach = snapshotService.takenByReattach(current.instanceDirName, snapshotId)
                val restored = snapshotService.restore(clientDir, current.instanceDirName, snapshotId, managed)
                // A rollback is a deliberate pin: stop following latest, or the
                // update just undone comes back on the next pass.
                repository.update(current.id) { it.withBuildOf(restored, provenance = reattach).copy(followLatest = false) }
                    ?: current.withBuildOf(restored, provenance = reattach).copy(followLatest = false)
            }
        }
    }

    /**
     * By id first, by version number second. The id is what the screen hands back
     * after a click; the number is what a caller with only a label can offer, and
     * it picks the first match because that is all it can do.
     */
    private fun List<ModrinthVersion>.resolve(target: String): ModrinthVersion? =
        firstOrNull { it.id == target } ?: firstOrNull { it.versionNumber == target }

    private suspend fun versionsNewestFirst(instance: PackInstance): List<ModrinthVersion> =
        client.listVersions(instance.packRef.id).sortedByDescending { it.datePublished }

    private fun available(instance: PackInstance, target: ModrinthVersion, direction: UpdateDirection) =
        UpdateCheck.Available(
            fromVersion = currentVersionOf(instance),
            toVersion = target.versionNumber,
            direction = direction,
            compat = compatOf(instance, target),
            // Not computed, and not computable: the file list lives inside the
            // archive, so producing one would mean downloading the pack in order
            // to decide whether to offer downloading the pack.
            plan = null,
            targetKey = target.id,
        )

    /**
     * Grades the change from what the version object already says.
     *
     * The loader VERSION is only inside the archive, so the installed one is
     * passed through. That is not a fudge: it is the sole thing separating
     * [CompatChange.Same] from [CompatChange.LoaderBump], and both of those are
     * safe, so the verdict this produces is exact and only the finer label is
     * unavailable. A changed Minecraft version or a swapped loader family --
     * the two that actually strand a world -- are both visible from here.
     */
    private fun compatOf(instance: PackInstance, target: ModrinthVersion): CompatChange {
        val installed = instance.cachedManifest
        return classifyCompat(
            installed = installed,
            targetMinecraft = target.gameVersions.firstOrNull() ?: installed?.minecraftVersion.orEmpty(),
            targetLoaderName = target.loaders.firstOrNull() ?: "vanilla",
            targetLoaderVersion = installed?.loaderVersion.orEmpty(),
        )
    }

    private fun ModrinthVersion.toPackBuild() = PackBuild(
        versionNumber = versionNumber,
        id = id,
        versionType = versionType,
        datePublished = datePublished,
        // Mirror concepts. Modrinth publishes no content fingerprint, and no
        // counts without the archive; null keeps "unknown" out of the UI rather
        // than drawing it as zero.
        fingerprint = null,
        changelog = changelog,
        modsCount = null,
        assetsCount = null,
        minecraftVersion = gameVersions.firstOrNull(),
        loaderName = loaders.firstOrNull(),
    )
}

/** A preview's archive folder, under the instance's reattach folder. */
private const val PREVIEW_PREFIX = "preview-"

/** A reattach's own archive folder, under the instance's reattach folder. */
private const val APPLY_PREFIX = "apply-"
