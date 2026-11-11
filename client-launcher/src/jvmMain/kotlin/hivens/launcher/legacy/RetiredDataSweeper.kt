package hivens.launcher.legacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Removes what the retired server path left behind, and only when told to.
 *
 * Nothing here runs on its own. The whole point of the surface above it is that
 * a player is told what is there and decides; a launcher that quietly reclaimed
 * five gigabytes of somebody's worlds would be right about the disk and wrong
 * about everything else.
 *
 * The bookkeeping files go with the last client rather than with the first,
 * because they describe the set: per-server profiles and the tray's cached
 * roster mean nothing once no client is left, and mean something while one is.
 */
class RetiredDataSweeper(
    private val dataDir: Path,
    /**
     * Called once before the first deletion, so a source the launcher still
     * reads is consumed while it is there. Today that is the default skin set:
     * it is extracted out of a client jar and cached, and `clients/` is where
     * an upgrading player's only jar lives until they install a pack.
     */
    private val beforeFirstDelete: suspend () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(RetiredDataSweeper::class.java)

    data class Swept(
        val clients: List<String>,
        val bytes: Long,
        val leftoversRemoved: Boolean,
        /**
         * Trees that lost some of their content and kept the rest. Separate from
         * [clients] because "could not be removed" and "is half gone" send the
         * reader to two different places.
         */
        val partial: List<String> = emptyList(),
    )

    /** How much of a tree went. */
    private enum class Removal { Gone, Partial, Untouched }

    /**
     * Deletes the named clients and, if that empties `clients/`, the bookkeeping
     * that only described them.
     *
     * A client that fails to delete is reported rather than thrown on: a locked
     * file in one tree must not leave the other six behind with nothing said.
     */
    suspend fun sweep(
        clients: List<RetiredClient>,
        /**
         * Bytes of a tree that are already a shared inode with an instance, which
         * removing the tree therefore does not free. An adopted source is almost
         * entirely this, and counting it as reclaimed would have the surface
         * announce gigabytes the disk never got back.
         */
        sharedBytesOf: (RetiredClient) -> Long = { 0L },
    ): Swept = withContext(io) {
        if (clients.isEmpty()) return@withContext Swept(emptyList(), 0L, false)
        beforeFirstDelete()

        val gone = mutableListOf<String>()
        val partial = mutableListOf<String>()
        var bytes = 0L
        for (client in clients) {
            currentCoroutineContext().ensureActive()
            when (deleteTree(client.dir)) {
                Removal.Gone -> {
                    gone += client.name
                    bytes += (client.sizeBytes - sharedBytesOf(client)).coerceAtLeast(0L)
                }
                Removal.Partial -> partial += client.name
                Removal.Untouched -> Unit
            }
        }

        // Empty is one answer and unreadable is another. Treating the second as
        // the first would remove the whole directory, including the folders the
        // reader had just chosen to keep.
        val remaining = clientsDir().entriesOrNull()
        val leftovers = remaining != null && remaining.isEmpty()
        if (leftovers) removeLeftovers()
        log.info(
            "sweep: removed {} client(s), {} partial, {} bytes, leftovers={}",
            gone.size, partial.size, bytes, leftovers,
        )
        Swept(gone, bytes, leftovers, partial)
    }

    /**
     * The files the retired path kept beside the clients: per-server launch
     * profiles, the roster the tray seeded from, the manifest cache each sync
     * compared against, and the list of names that sync was told to leave alone.
     * None is read by anything now.
     */
    private fun removeLeftovers() {
        val targets = listOf(
            dataDir.resolve("manifest-cache"),
            dataDir.resolve(RETIRED_PROFILES_FILE),
            dataDir.resolve(RETIRED_SERVERS_CACHE_FILE),
            dataDir.resolve(RETIRED_PROTECTED_PATHS_FILE),
            clientsDir(),
        )
        for (path in targets) {
            if (!path.exists()) continue
            if (deleteTree(path) == Removal.Gone) log.info("sweep: removed {}", path.fileName)
        }
    }

    /**
     * Removes a tree, and answers how much of it went.
     *
     * One locked file used to abort the whole walk and read back as "untouched",
     * which is the opposite of what the reader is looking at: the other several
     * thousand files are already gone. Each entry is therefore its own attempt,
     * and what survives decides the answer.
     */
    private fun deleteTree(path: Path): Removal {
        if (!path.exists()) return Removal.Gone
        var removed = 0
        try {
            Files.walk(path).use { tree ->
                tree.sorted(Comparator.reverseOrder()).forEach { entry ->
                    try {
                        if (Files.deleteIfExists(entry)) removed++
                    } catch (e: IOException) {
                        log.warn("sweep: could not remove {}", entry, e)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("sweep: could not read {} while removing it", path, e)
        }
        return when {
            !path.exists() -> Removal.Gone
            removed > 0 -> Removal.Partial
            else -> Removal.Untouched
        }
    }

    private fun clientsDir(): Path = dataDir.resolve("clients")

    /**
     * What the directory holds, or null when that cannot be read.
     *
     * Null rather than an empty list, because the caller removes the directory on
     * an empty answer. A listing that failed and said "empty" is how a permission
     * error turns into the deletion of everything the reader kept.
     */
    private fun Path.entriesOrNull(): List<Path>? {
        if (!exists() || !isDirectory()) return emptyList()
        return runCatching { Files.newDirectoryStream(this).use { it.toList() } }
            .onFailure { log.warn("sweep: could not list {}, leaving the leftovers alone", this, it) }
            .getOrNull()
    }

    private companion object {
        /**
         * Named here rather than in the storage constants, which no longer carry
         * them: they are not the launcher's files any more, they are the retired
         * path's, and this is the one place that still has to know their names --
         * to remove them.
         */
        const val RETIRED_PROFILES_FILE = "profiles.json"
        const val RETIRED_SERVERS_CACHE_FILE = "servers-cache.json"
        const val RETIRED_PROTECTED_PATHS_FILE = "protected-paths.json"
    }
}
