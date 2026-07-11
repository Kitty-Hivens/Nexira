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
 * Read synchronously at process startup so a consumer has a roster immediately,
 * before the first [SmartyCraftServerListService.fetchDashboardData] returns
 * (the live fetch can take a few seconds). Stale-while-revalidate: the cached
 * entries are served first; the live fetch then overwrites both the in-memory
 * state and the cache. First-ever launch (no cache file yet) returns the empty
 * list -- honest, because we genuinely don't know the roster yet.
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
 *    an empty roster until the live fetch arrives.
 *  - Save writes through a `<file>.tmp` rename so a crash mid-write
 *    leaves either the previous valid cache or the new one, never
 *    a half-baked file.
 */
class JsonServerListCacheStore(
    private val file: Path,
    private val json: Json,
) : ServerListCacheStore {

    private val log = LoggerFactory.getLogger(JsonServerListCacheStore::class.java)

    // Set by load() when the file's schema_version is newer than this build
    // understands. The cache self-heals from the next live fetch, so the only
    // cost of not writing back is one stale-until-refresh read -- worth it to
    // never let an older binary downgrade a newer build's file.
    @Volatile private var readOnly = false

    override fun load(): List<ServerProfile> {
        if (!Files.exists(file)) return emptyList()
        return try {
            val parsed = json.decodeFromString<ServersCacheFile>(Files.readString(file))
            if (parsed.schemaVersion > SCHEMA_VERSION) {
                readOnly = true
                log.warn(
                    "Servers cache at {} is schema_version {} > supported {} -- written by a newer build. " +
                        "Loading read-only; this session will not write it back.",
                    file, parsed.schemaVersion, SCHEMA_VERSION,
                )
            }
            parsed.servers
        } catch (e: Exception) {
            log.error("Failed to load servers cache at {} -- starting empty", file, e)
            emptyList()
        }
    }

    override suspend fun save(servers: List<ServerProfile>) {
        if (readOnly) {
            log.debug("Servers cache at {} is from a newer build -- skipping write-back", file)
            return
        }
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
