package hivens.launcher.update

import hivens.core.io.readUtf8
import hivens.core.data.PackInstance
import hivens.core.io.deleteTree
import hivens.core.io.AtomicFiles
import hivens.core.update.PackSnapshot
import hivens.launcher.smrt.SmrtSyncService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * Captures and restores pre-update snapshots of a pack instance so an amber
 * update (a structural MC / loader change) can be undone. Snapshots live outside
 * the instance dir under `<dataDir>/snapshots/<instanceDirName>/<id>/`, so they
 * are never seen by the sync or the on-disk scan.
 *
 * Capture hardlinks each managed file where the filesystem allows and copies
 * otherwise. Hardlinks are cheap AND correct here: the apply replaces a file via
 * atomic move (a new inode) and removes one via unlink, so a link taken before
 * the apply keeps pointing at the pre-update bytes. Only manifest-declared files
 * are ever touched -- worlds and user-added content are out of scope by construction.
 */
class PackSnapshotService(
    private val dataDir: Path,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(PackSnapshotService::class.java)

    private fun rootFor(instanceDirName: String): Path =
        dataDir.resolve("snapshots").resolve(instanceDirName)

    /**
     * Capture the current bytes of the [managedRealPaths] that exist, plus the
     * pre-update [instance] record, into a new snapshot [id].
     *
     * A managed path that is a folder or a link is kept as one: the folder so an
     * empty one comes back, the link by where it points, so a restore puts the link
     * back rather than a copy of what it led to. [reattach] marks a snapshot taken
     * by a reattach, whose record also says where the pack came from, see
     * [takenByReattach].
     */
    fun capture(
        clientDir: Path,
        instance: PackInstance,
        managedRealPaths: Set<String>,
        id: String,
        createdAtEpoch: Long,
        reattach: Boolean = false,
    ): PackSnapshot {
        val root = clientDir.normalize()
        val dir = rootFor(instance.instanceDirName).resolve(id)
        val filesDir = dir.resolve("files")
        Files.createDirectories(filesDir)
        val captured = ArrayList<String>()
        val folders = ArrayList<String>()
        val links = LinkedHashMap<String, String>()
        // The pack's files, plus the two the instance keeps about itself. Those two
        // are not manifest paths, so a scan never lists them and a restore driven by
        // the manifest cannot put them back: without capturing them by name here, a
        // rollback leaves the previous build's mods under the roster of the build it
        // rolled back from, and that roster is what the next launch deletes by.
        for (rel in managedRealPaths + SmrtSyncService.INSTANCE_STATE_FILES) {
            val src = root.resolve(rel).normalize()
            if (!src.startsWith(root)) continue
            if (Files.isSymbolicLink(src)) {
                links[rel] = Files.readSymbolicLink(src).toString()
                continue
            }
            if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
                folders += rel
                continue
            }
            if (!Files.isRegularFile(src)) continue
            val dst = filesDir.resolve(rel)
            Files.createDirectories(dst.parent)
            linkOrCopy(src, dst, replace = false)
            captured += rel
        }
        val record = SnapshotRecord.of(id, createdAtEpoch, instance.pinnedPackVersion, instance, captured, folders, links, reattach)
        AtomicFiles.writeString(dir.resolve("snapshot.json"), json.encodeToString(SnapshotRecord.serializer(), record))
        log.info("snapshot: captured {} file(s) for instance {} as {}", captured.size, instance.instanceDirName, id)
        return record.toPublic()
    }

    /**
     * Restore snapshot [id]: put every captured file back, delete a managed path or
     * an instance state file the apply created (present now, absent from the
     * snapshot), and return the
     * pre-update [PackInstance] for the caller to re-persist. [managedRealPaths]
     * is the current (post-update) managed set whose non-captured members are the
     * files to remove.
     */
    fun restore(
        clientDir: Path,
        instanceDirName: String,
        id: String,
        managedRealPaths: Set<String>,
    ): PackInstance {
        val root = clientDir.normalize()
        val dir = rootFor(instanceDirName).resolve(id)
        val record = readRecord(dir)
        val capturedSet = record.files.toSet() + record.folders + record.links.keys

        // Track every path the restore could not reconcile: a failed rollback that
        // silently reported success left the instance half-updated with the registry
        // claiming it was rolled back. Surfacing it lets the caller keep the snapshot
        // (for a manual retry) and tell the user rather than trust a broken state.
        val failures = ArrayList<String>()

        // The state files go with the managed set: one the snapshot did not hold was
        // absent before the update, so any copy now on disk is the apply's. Left in
        // place, the roster written for the build being undone becomes the next
        // launch's delete list over the build that was just put back.
        for (rel in managedRealPaths + SmrtSyncService.INSTANCE_STATE_FILES) {
            if (rel in capturedSet) continue
            val live = root.resolve(rel).normalize()
            if (live.startsWith(root)) {
                runCatching { Files.deleteIfExists(live) }.onFailure { failures += "delete $rel" }
            }
        }
        for (rel in record.folders) {
            val dst = root.resolve(rel).normalize()
            if (!dst.startsWith(root)) continue
            runCatching { Files.createDirectories(dst) }.onFailure { failures += "restore folder $rel" }
        }
        for ((rel, target) in record.links) {
            val dst = root.resolve(rel).normalize()
            if (!dst.startsWith(root)) continue
            // Over whatever stands there now. An update replaces a linked file with a
            // regular one by moving its download over the link, and that file is
            // exactly what the rollback is undoing.
            runCatching {
                Files.createDirectories(dst.parent)
                if (Files.isDirectory(dst, LinkOption.NOFOLLOW_LINKS)) deleteTree(dst, clearReadOnly = false) else Files.deleteIfExists(dst)
                Files.createSymbolicLink(dst, Path.of(target))
            }.onFailure { failures += "restore link $rel" }
        }
        for (rel in record.files) {
            val src = dir.resolve("files").resolve(rel)
            val dst = root.resolve(rel).normalize()
            if (!dst.startsWith(root)) continue
            if (!Files.isRegularFile(src)) {
                failures += "restore $rel (snapshot bytes missing)"
                continue
            }
            Files.createDirectories(dst.parent)
            runCatching { linkOrCopy(src, dst, replace = true) }.onFailure { failures += "restore $rel" }
        }

        if (failures.isNotEmpty()) {
            log.error("snapshot: restore of {} from {} left {} item(s) unresolved: {}", instanceDirName, id, failures.size, failures)
            throw SnapshotRestoreException(instanceDirName, id, failures)
        }
        log.info("snapshot: restored instance {} from {}", instanceDirName, id)
        return record.instance
    }

    /**
     * Whether snapshot [id] was taken by a reattach. Its record then names where the
     * pack came from as well as which build it was on, and a rollback to it puts
     * both back, see [hivens.launcher.record.withBuildOf]. False for a snapshot that
     * cannot be read.
     */
    fun takenByReattach(instanceDirName: String, id: String): Boolean =
        runCatching { readRecord(rootFor(instanceDirName).resolve(id)).reattach }.getOrDefault(false)

    /** Snapshots for an instance, newest first. */
    fun list(instanceDirName: String): List<PackSnapshot> {
        val root = rootFor(instanceDirName)
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { runCatching { readRecord(it).toPublic() }.getOrNull() }
                .filter { it != null }
                .map { it!! }
                .collect(Collectors.toList())
        }.sortedByDescending { it.createdAtEpoch }
    }

    /** Keep the [keepLast] newest snapshots, delete the rest. */
    fun prune(instanceDirName: String, keepLast: Int) {
        list(instanceDirName).drop(keepLast.coerceAtLeast(0)).forEach { delete(instanceDirName, it.id) }
    }

    /** Every snapshot of an instance, for one that is gone: nothing can restore into it now. */
    fun deleteAll(instanceDirName: String) {
        val root = rootFor(instanceDirName)
        runCatching { if (Files.exists(root)) deleteTree(root, clearReadOnly = false) }
            .onFailure { log.warn("snapshot: failed to remove the snapshots of {}", instanceDirName, it) }
    }

    /**
     * Without clearing read-only marks: a snapshot holds the live files by hardlink,
     * and on Windows clearing the mark through the link clears it on the file the
     * player marked.
     */
    fun delete(instanceDirName: String, id: String) {
        val dir = rootFor(instanceDirName).resolve(id)
        runCatching { deleteTree(dir, clearReadOnly = false) }
            .onFailure { log.warn("snapshot: failed to delete {} for {}", id, instanceDirName, it) }
    }

    private fun readRecord(dir: Path): SnapshotRecord =
        json.decodeFromString(SnapshotRecord.serializer(), readUtf8(dir.resolve("snapshot.json")))

    private fun linkOrCopy(src: Path, dst: Path, replace: Boolean) {
        if (replace) {
            val _ = runCatching { Files.deleteIfExists(dst) }
        }
        val _ = runCatching { Files.createLink(dst, src) }
            .recoverCatching {
                if (replace) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.copy(src, dst)
                }
            }
            .getOrThrow()
    }

    /**
     * What `snapshot.json` holds.
     *
     * Folders, links and the reattach mark came later, and a launcher from before them
     * ignores fields it does not know. Reading such a snapshot it would delete the
     * folders and links it was meant to restore, and put a reattach back onto the
     * source instead of Local. So a snapshot that has any of them writes its file list
     * under [capturedFiles] and leaves [capturedPaths] null, which an older launcher
     * requires and fails to read: it refuses the snapshot rather than restore half of
     * it. A snapshot with none of them is written the old way and stays readable to it.
     */
    @Serializable
    private data class SnapshotRecord(
        val id: String,
        val createdAtEpoch: Long,
        val fromVersion: String?,
        val instance: PackInstance,
        val capturedPaths: List<String>? = null,
        val capturedFiles: List<String>? = null,
        val folders: List<String> = emptyList(),
        /** Each captured link by where it pointed, written back as a link. */
        val links: Map<String, String> = emptyMap(),
        val reattach: Boolean = false,
    ) {
        /** The captured files, under whichever name they were written. */
        val files: List<String> get() = capturedFiles ?: capturedPaths.orEmpty()

        fun toPublic() = PackSnapshot(id, createdAtEpoch, fromVersion)

        companion object {
            fun of(
                id: String,
                createdAtEpoch: Long,
                fromVersion: String?,
                instance: PackInstance,
                files: List<String>,
                folders: List<String>,
                links: Map<String, String>,
                reattach: Boolean,
            ): SnapshotRecord {
                val olderCanRead = folders.isEmpty() && links.isEmpty() && !reattach
                return SnapshotRecord(
                    id, createdAtEpoch, fromVersion, instance,
                    capturedPaths = files.takeIf { olderCanRead },
                    capturedFiles = files.takeUnless { olderCanRead },
                    folders = folders,
                    links = links,
                    reattach = reattach,
                )
            }
        }
    }
}

/**
 * A snapshot restore that could not fully reconstruct the pre-update state: a
 * captured file could not be put back, or an apply-created file could not be
 * removed. [failures] names the unresolved paths. The snapshot is left intact so a
 * manual restore can retry rather than the caller trusting a half-restored state.
 */
class SnapshotRestoreException(
    val instanceDirName: String,
    val snapshotId: String,
    val failures: List<String>,
) : Exception("snapshot restore of $instanceDirName from $snapshotId left ${failures.size} item(s) unresolved: $failures")
