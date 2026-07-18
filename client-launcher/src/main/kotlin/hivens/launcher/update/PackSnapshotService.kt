package hivens.launcher.update

import hivens.core.data.PackInstance
import hivens.core.io.AtomicFiles
import hivens.core.update.PackSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
     */
    fun capture(
        clientDir: Path,
        instance: PackInstance,
        managedRealPaths: Set<String>,
        id: String,
        createdAtEpoch: Long,
    ): PackSnapshot {
        val root = clientDir.normalize()
        val dir = rootFor(instance.instanceDirName).resolve(id)
        val filesDir = dir.resolve("files")
        Files.createDirectories(filesDir)
        val captured = ArrayList<String>()
        for (rel in managedRealPaths) {
            val src = root.resolve(rel).normalize()
            if (!src.startsWith(root) || !Files.isRegularFile(src)) continue
            val dst = filesDir.resolve(rel)
            Files.createDirectories(dst.parent)
            linkOrCopy(src, dst, replace = false)
            captured += rel
        }
        val record = SnapshotRecord(id, createdAtEpoch, instance.pinnedPackVersion, instance, captured)
        AtomicFiles.writeString(dir.resolve("snapshot.json"), json.encodeToString(SnapshotRecord.serializer(), record))
        log.info("snapshot: captured {} file(s) for instance {} as {}", captured.size, instance.instanceDirName, id)
        return record.toPublic()
    }

    /**
     * Restore snapshot [id]: put every captured file back, delete a managed path
     * the apply created (present now, absent from the snapshot), and return the
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
        val capturedSet = record.capturedPaths.toSet()

        // Track every path the restore could not reconcile: a failed rollback that
        // silently reported success left the instance half-updated with the registry
        // claiming it was rolled back. Surfacing it lets the caller keep the snapshot
        // (for a manual retry) and tell the user rather than trust a broken state.
        val failures = ArrayList<String>()

        for (rel in managedRealPaths) {
            if (rel in capturedSet) continue
            val live = root.resolve(rel).normalize()
            if (live.startsWith(root)) {
                runCatching { Files.deleteIfExists(live) }.onFailure { failures += "delete $rel" }
            }
        }
        for (rel in record.capturedPaths) {
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

    /** Snapshots for an instance, newest first. */
    fun list(instanceDirName: String): List<PackSnapshot> {
        val root = rootFor(instanceDirName)
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { runCatching { readRecord(it).toPublic() }.getOrNull() }
                .filter { it != null }
                .map { it!! }
                .toList()
        }.sortedByDescending { it.createdAtEpoch }
    }

    /** Keep the [keepLast] newest snapshots, delete the rest. */
    fun prune(instanceDirName: String, keepLast: Int) {
        list(instanceDirName).drop(keepLast.coerceAtLeast(0)).forEach { delete(instanceDirName, it.id) }
    }

    fun delete(instanceDirName: String, id: String) {
        val dir = rootFor(instanceDirName).resolve(id)
        runCatching { dir.toFile().deleteRecursively() }
            .onFailure { log.warn("snapshot: failed to delete {} for {}", id, instanceDirName, it) }
    }

    private fun readRecord(dir: Path): SnapshotRecord =
        json.decodeFromString(SnapshotRecord.serializer(), Files.readString(dir.resolve("snapshot.json")))

    private fun linkOrCopy(src: Path, dst: Path, replace: Boolean) {
        if (replace) runCatching { Files.deleteIfExists(dst) }
        runCatching { Files.createLink(dst, src) }
            .recoverCatching {
                if (replace) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.copy(src, dst)
                }
            }
            .getOrThrow()
    }

    @Serializable
    private data class SnapshotRecord(
        val id: String,
        val createdAtEpoch: Long,
        val fromVersion: String?,
        val instance: PackInstance,
        val capturedPaths: List<String>,
    ) {
        fun toPublic() = PackSnapshot(id, createdAtEpoch, fromVersion)
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
