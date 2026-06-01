package hivens.launcher

import hivens.core.api.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import hivens.core.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * Disk snapshot of the most recently fetched dashboard server list.
 *
 * Read synchronously at process startup so the tray menu's first
 * published DBusMenu layout already carries real servers, not the
 * "(No servers)" placeholder users see when they right-click during
 * the 0.5-3s window between [TrayManager.init] and the first
 * successful [SmartyCraftServerListService.fetchDashboardData].
 *
 * Stale-while-revalidate: cached entries seed the tray; the live
 * fetch then overwrites both the in-memory state and the cache.
 * First-ever launch (no cache file yet) still shows the empty
 * fallback -- honest, because we genuinely don't know the list yet.
 */
interface ServerListCacheStore {

    /** Synchronous load. Safe to call before any background scheduler is up. */
    fun load(): List<ServerProfile>

    /** Atomic write. Invoked from a background coroutine after each successful fetch. */
    suspend fun save(servers: List<ServerProfile>)

    /**
     * Discardable cache. Used by unit tests that exercise unrelated
     * behaviour (single-flight, in-memory caching) and don't want
     * to set up a temp file.
     */
    object NoOp : ServerListCacheStore {
        override fun load(): List<ServerProfile> = emptyList()
        override suspend fun save(servers: List<ServerProfile>) = Unit
    }
}

/**
 * JSON-on-disk [ServerListCacheStore].
 *
 * Wire format:
 * ```
 * { "schema_version": 1, "servers": [ <ServerProfile>, ... ] }
 * ```
 *
 * The versioned envelope mirrors [JsonPackRepository] so the same
 * schema-evolution story applies: a future bump branches on
 * `schema_version` cleanly instead of guessing whether a bare array
 * is "valid empty" or "v0 corruption".
 *
 * Behaviour contract:
 *  - Missing file -> empty list, no log noise (cold start).
 *  - Malformed JSON -> empty list AND error logged. The launcher
 *    must not die on a half-written cache; the worst real cost is
 *    the user seeing "(No servers)" on the next right-click until
 *    the live fetch arrives.
 *  - Save writes through a `<file>.tmp` rename so a crash mid-write
 *    leaves either the previous valid cache or the new one, never
 *    a half-baked file.
 */
class JsonServerListCacheStore(
    private val file: Path,
    private val json: Json,
) : ServerListCacheStore {

    private val log = LoggerFactory.getLogger(JsonServerListCacheStore::class.java)

    override fun load(): List<ServerProfile> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString<ServersCacheFile>(Files.readString(file)).servers
        } catch (e: Exception) {
            log.error("Failed to load servers cache at {} -- starting empty", file, e)
            emptyList()
        }
    }

    override suspend fun save(servers: List<ServerProfile>) {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = ServersCacheFile(schemaVersion = SCHEMA_VERSION, servers = servers)
                AtomicFiles.writeString(file, json.encodeToString(snapshot))
            } catch (e: Exception) {
                log.error("Failed to persist servers cache at {}", file, e)
            }
        }
    }

    @Serializable
    private data class ServersCacheFile(
        @SerialName("schema_version") val schemaVersion: Int,
        val servers: List<ServerProfile>,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
