package hivens.launcher.instance

import hivens.core.data.MultiplayerServerEntry
import hivens.launcher.nbt.Nbt
import hivens.launcher.nbt.NbtCompound
import hivens.launcher.nbt.NbtValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Reads `<instanceDir>/servers.dat` -- the vanilla "Multiplayer ->
 * Add Server" list -- into a sequence of [MultiplayerServerEntry].
 *
 * Missing file -> empty list (the user has never opened the
 * multiplayer screen, or this is a fresh instance). Corrupt file
 * -> empty list AND a log line (degrades gracefully; the worst
 * cost is the Worlds tab's "joined servers" section reading empty).
 */
class ServersDatReader {

    private val log = LoggerFactory.getLogger(ServersDatReader::class.java)

    suspend fun read(instanceDir: Path): List<MultiplayerServerEntry> = withContext(Dispatchers.IO) {
        val file = instanceDir.resolve("servers.dat")
        if (!file.exists() || !file.isRegularFile()) return@withContext emptyList()

        val root = try {
            // servers.dat is NOT GZIP-compressed in vanilla MC. The
            // launcher passes gzipped=false; if a future MC release
            // changes that, NbtException at parse time would point
            // at the wrong call site here, easy to flip.
            //
            // Catch broadly: the in-tree Nbt parser also surfaces
            // IOException on truncated payloads and the occasional
            // EOFException / IllegalArgumentException on malformed
            // tag headers. A corrupt servers.dat must not blow up
            // the Worlds tab; the graceful path is "no servers".
            Files.newInputStream(file).use { Nbt.read(it, gzipped = false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("servers.dat at {} did not parse: {}", file, e.message)
            return@withContext emptyList()
        }

        val serversList = root.value.list("servers")
        if (serversList == null || serversList.items.isEmpty()) return@withContext emptyList()

        serversList.items.mapNotNull { entry ->
            val compound = (entry as? NbtValue.Compound)?.value ?: return@mapNotNull null
            entryFromCompound(compound)
        }
    }

    private fun entryFromCompound(c: NbtCompound): MultiplayerServerEntry {
        val ip = c.string("ip") ?: ""
        val rawName = c.string("name").orEmpty()
        return MultiplayerServerEntry(
            name = rawName.ifBlank { ip },
            ip = ip,
            iconBase64 = c.string("icon"),
            acceptTexturesMode = c.byte("acceptTextures"),
            hidden = (c.byte("hidden") ?: 0).toInt() != 0,
        )
    }
}
