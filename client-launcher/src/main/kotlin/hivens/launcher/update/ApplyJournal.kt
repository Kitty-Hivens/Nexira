package hivens.launcher.update

import hivens.core.io.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A durable "an update is mid-apply" marker. [begin] is written atomically after
 * the pre-update snapshot is captured but BEFORE the first file mutation; [complete]
 * clears it once the apply commits (or an in-process failure has already restored the
 * snapshot). If the process dies in between -- SIGKILL, OOM, power loss -- the marker
 * survives and [ApplyRecovery] rolls the instance back on the next start.
 *
 * One marker per instance dir, under `<dataDir>/apply-journal/<instanceDirName>.json`
 * (outside the instance dir, so a sync or an on-disk scan never sees it).
 */
class ApplyJournal(
    private val dataDir: Path,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(ApplyJournal::class.java)

    private fun dir(): Path = dataDir.resolve("apply-journal")
    private fun fileFor(instanceDirName: String): Path = dir().resolve("$instanceDirName.json")

    /** Record an in-flight apply. Atomic write -- a crash leaves the old or new marker, never a partial one. */
    fun begin(entry: PendingApply) {
        AtomicFiles.writeString(fileFor(entry.instanceDirName), json.encodeToString(PendingApply.serializer(), entry))
    }

    /** Clear the marker for [instanceDirName]; a missing marker is a no-op. */
    fun complete(instanceDirName: String) {
        runCatching { Files.deleteIfExists(fileFor(instanceDirName)) }
            .onFailure { log.warn("apply-journal: failed to clear marker for {}", instanceDirName, it) }
    }

    /** Every in-flight apply marker still on disk. Unreadable markers are skipped. */
    fun listPending(): List<PendingApply> {
        val d = dir()
        if (!Files.isDirectory(d)) return emptyList()
        return Files.list(d).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }
                .map { runCatching { json.decodeFromString(PendingApply.serializer(), Files.readString(it)) }.getOrNull() }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
    }
}

/**
 * The pre-update state an interrupted apply can be rolled back to: which snapshot
 * holds the pre-update bytes + record, and the managed path set whose non-captured
 * members are the files the apply added (to remove on restore).
 */
@Serializable
data class PendingApply(
    val instanceId: String,
    val instanceDirName: String,
    val snapshotId: String,
    val fromVersion: String?,
    val toVersion: String,
    val managedPaths: List<String>,
    val startedAtEpoch: Long,
)
