package hivens.launcher.legacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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

    data class Swept(val clients: List<String>, val bytes: Long, val leftoversRemoved: Boolean)

    /**
     * Deletes the named clients and, if that empties `clients/`, the bookkeeping
     * that only described them.
     *
     * A client that fails to delete is reported rather than thrown on: a locked
     * file in one tree must not leave the other six behind with nothing said.
     */
    suspend fun sweep(clients: List<RetiredClient>): Swept = withContext(io) {
        if (clients.isEmpty()) return@withContext Swept(emptyList(), 0L, false)
        beforeFirstDelete()

        val gone = mutableListOf<String>()
        var bytes = 0L
        for (client in clients) {
            currentCoroutineContext().ensureActive()
            if (deleteTree(client.dir)) {
                gone += client.name
                bytes += client.sizeBytes
            }
        }

        val leftovers = clientsDir().let { !it.isDirectory() || it.listOrEmpty().isEmpty() }
        if (leftovers) removeLeftovers()
        log.info("sweep: removed {} client(s), {} bytes, leftovers={}", gone.size, bytes, leftovers)
        Swept(gone, bytes, leftovers)
    }

    /**
     * The files the retired path kept beside the clients: per-server launch
     * profiles, the roster the tray seeded from, and the manifest cache each sync
     * compared against. None is read by anything now.
     */
    private fun removeLeftovers() {
        val targets = listOf(
            dataDir.resolve("manifest-cache"),
            dataDir.resolve(RETIRED_PROFILES_FILE),
            dataDir.resolve(RETIRED_SERVERS_CACHE_FILE),
            clientsDir(),
        )
        for (path in targets) {
            if (!path.exists()) continue
            if (deleteTree(path)) log.info("sweep: removed {}", path.fileName)
        }
    }

    private fun deleteTree(path: Path): Boolean = runCatching {
        if (!path.exists()) return true
        Files.walk(path).use { tree ->
            tree.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        !path.exists()
    }.getOrElse {
        log.warn("sweep: could not remove {}", path, it)
        false
    }

    private fun clientsDir(): Path = dataDir.resolve("clients")

    private fun Path.listOrEmpty(): List<Path> =
        runCatching { Files.newDirectoryStream(this).use { it.toList() } }.getOrDefault(emptyList())

    private companion object {
        /**
         * Named here rather than in the storage constants, which no longer carry
         * them: they are not the launcher's files any more, they are the retired
         * path's, and this is the one place that still has to know their names --
         * to remove them.
         */
        const val RETIRED_PROFILES_FILE = "profiles.json"
        const val RETIRED_SERVERS_CACHE_FILE = "servers-cache.json"
    }
}
