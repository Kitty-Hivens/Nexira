package hivens.launcher.update

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.io.InstanceMutationLock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

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
 * added stays where they put it.
 */
class ModrinthPackUpdater(
    private val client: ModrinthClient,
    private val installer: MrpackInstaller,
    private val repository: IPackRepository,
    private val snapshotService: PackSnapshotService,
    private val dataDir: Path,
) : PackUpdater {

    private val log = LoggerFactory.getLogger(ModrinthPackUpdater::class.java)

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
            val target = ordered.firstOrNull { it.versionNumber == targetVersion }
                ?: return@withContext UpdateCheck.UpToDate
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
            ordered.firstOrNull { it.versionNumber == targetVersion }
                ?: throw IOException("Modrinth has no version '$targetVersion' of pack ${instance.packRef.id}")
        } else {
            ordered.firstOrNull() ?: throw IOException("Modrinth lists no versions of pack ${instance.packRef.id}")
        }
        if (currentVersionOf(instance) == target.versionNumber) return UpdateOutcome.AlreadyCurrent

        val clientDir = clientDirOf(instance)
        val compat = compatOf(instance, target)

        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: instance
                // A structural change is the one that strands a world and the mods
                // the player added on top, so it gets a snapshot it can be undone
                // from. A safe re-sync does not pay for one.
                if (!compat.isSafe) {
                    val managed = PackFileRecord.read(clientDir).keys
                    snapshotService.capture(clientDir, current, managed, UUID.randomUUID().toString(), Instant.now().epochSecond)
                }

                // A directory, so the archive path does not exist yet: downloadTo
                // skips a target that is already on disk, and createTempFile
                // creates one, so handing it a temp FILE downloaded nothing and
                // left an empty archive to be opened as a zip.
                val scratch = Files.createTempDirectory("mrpack-update-")
                val archive = scratch.resolve("pack.mrpack")
                try {
                    client.downloadTo(target.primaryFile().url, archive)
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
                    installer.update(
                        instance = current,
                        mrpack = archive,
                        source = MrpackSource(current.packRef.origin, current.packRef.id, target.versionNumber),
                        progress = progress ?: { _, _, _ -> },
                    )
                } finally {
                    runCatching { Files.deleteIfExists(archive) }
                    runCatching { Files.deleteIfExists(scratch) }
                }
                log.info("modrinth update: {} -> {}", instance.instanceDirName, target.versionNumber)
                UpdateOutcome.Applied(target.versionNumber, compat, plan = null)
            }
        }
    }

    override fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
        snapshotService.list(instance.instanceDirName)

    override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
        val clientDir = clientDirOf(instance)
        return InstanceMutationLock.withLock(clientDir) {
            withContext(Dispatchers.IO) {
                val current = repository.get(instance.id) ?: instance
                val managed = PackFileRecord.read(clientDir).keys
                val restored = snapshotService.restore(clientDir, current.instanceDirName, snapshotId, managed)
                // A rollback is a deliberate pin: stop following latest, or the
                // update just undone comes back on the next pass.
                val pinned = restored.copy(followLatest = false)
                repository.put(pinned)
                pinned
            }
        }
    }

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
