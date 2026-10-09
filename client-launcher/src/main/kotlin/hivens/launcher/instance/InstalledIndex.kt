package hivens.launcher.instance

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * What an instance holds, file by file, and which catalogue build each file is.
 *
 * One reading for every question of that shape: whether a project is already
 * installed, which file to replace when another build of it is chosen, whether a
 * name is taken. They used to be asked of `mods/` alone, so a resource pack
 * installed a minute earlier read as absent.
 *
 * Nothing is written into the instance. The hashes come through [hashOf], which
 * in the app is the file-hash cache kept beside the other caches, so a folder that
 * has not changed is not read again. The catalogue's answer is asked once per
 * reading, for every file at once.
 */
class InstalledIndex(
    private val scanner: InstanceContentScanner,
    private val modrinth: ModrinthClient,
    /** The file's sha1, or null when it cannot be read. */
    private val hashOf: suspend (Path) -> String?,
) {
    private val log = LoggerFactory.getLogger(InstalledIndex::class.java)

    /** One file: where it is, its bytes by hash, the build it is when the catalogue knows, and whether it is on. */
    data class Entry(
        val ref: ContentRef,
        val sha1: String,
        val version: ModrinthVersion?,
        val enabled: Boolean,
    ) {
        val projectId: String? get() = version?.projectId
    }

    /**
     * A reading. [complete] is false when the folders could not be listed, an archive
     * in them could not be read or hashed, or the catalogue could not be asked. The last
     * leaves every [Entry.version] null and [projects] empty: a folder nobody could
     * identify, not a folder holding nothing. An empty folder is complete, since
     * there was nothing to ask about.
     */
    class Snapshot(val entries: List<Entry>, val complete: Boolean) {
        val projects: Set<String> = entries.mapNotNullTo(LinkedHashSet()) { it.projectId }

        /** Every file of [projectId], enabled ones first. */
        fun of(projectId: String): List<Entry> =
            entries.filter { it.projectId == projectId }.sortedByDescending { it.enabled }

        /** A file holding exactly these bytes, whatever it is called and wherever it sits. */
        fun withSha1(sha1: String): Entry? = entries.firstOrNull { it.sha1.equals(sha1, ignoreCase = true) }

        /** The file at [ref], on or off. */
        fun at(ref: ContentRef): Entry? = entries.firstOrNull { it.ref == ref }
    }

    suspend fun read(instanceDir: Path): Snapshot = withContext(Dispatchers.IO) {
        // A folder that could not be listed, or a file that could not be hashed, is
        // a reading with a hole in it, and says so: read as complete it would let an
        // install place a second build beside a file it could not see.
        var complete = true
        val items = try {
            val report = scanner.scanReport(instanceDir)
            // An archive whose metadata did not parse is skipped by the scan and still
            // loaded by the game, so it is a file this reading cannot see.
            if (report.unreadable.isNotEmpty()) {
                log.warn("{} archive(s) in {} could not be read: {}", report.unreadable.size, instanceDir, report.unreadable.map { it.fileName })
                complete = false
            }
            report.items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not scan {}: {}", instanceDir, e.message)
            complete = false
            emptyList()
        }
        val hashed = items.mapNotNull { c -> hashOf(c.pathIn(instanceDir))?.let { c to it } }
        if (hashed.size < items.size) complete = false
        val versions = try {
            modrinth.versionsForHashes(hashed.map { it.second })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not identify the content of {}: {}", instanceDir, e.message)
            complete = false
            emptyMap()
        }
        Snapshot(
            entries = hashed.map { (c, hash) -> Entry(ContentRef(c.kind, c.fileName), hash, versions[hash], c.enabled) },
            complete = complete,
        )
    }
}
