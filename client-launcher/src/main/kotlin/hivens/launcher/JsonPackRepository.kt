package hivens.launcher

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.io.AtomicFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON-on-disk [IPackRepository]. Persists the launcher's installed
 * [PackInstance] registry to a single file under the platform data
 * directory (resolved by the caller; we just open the path we are
 * given).
 *
 * Wire format:
 * ```
 * { "schema_version": 1, "instances": [ <PackInstance>, ... ] }
 * ```
 *
 * The wrapper object exists so a future schema bump (added required
 * fields, renamed enums, etc.) can branch on `schema_version`
 * without falling into "is this an empty array or a bad file" v0
 * ambiguity.
 *
 * Behavior contract:
 *  - Missing file -> empty list, no error logged (cold start).
 *  - Unreadable / malformed JSON -> empty list AND error logged
 *    (keeps the launcher alive rather than crashing into the
 *    untouched-state condition).
 *  - Mutations land in memory first, then write the whole registry
 *    atomically via `<file>.tmp` + ATOMIC_MOVE rename. The cost is
 *    O(N) per mutation; N stays small in practice (dozens of packs
 *    at most), so the tradeoff favours simplicity over an event-log
 *    layout we would have to compact.
 *  - File I/O hops to [Dispatchers.IO] so the calling coroutine
 *    (often Main, since Library updates are UI-driven) does not
 *    block on disk.
 */
class JsonPackRepository(
    private val file: Path,
    private val json: Json,
) : IPackRepository {

    private val log   = LoggerFactory.getLogger(JsonPackRepository::class.java)
    private val mutex = Mutex()

    // Set by load() when the file's schema_version is newer than this build
    // understands. A newer build wrote it; we read best-effort but never write
    // back, so an older binary cannot downgrade and clobber the newer data.
    @Volatile private var readOnly = false

    private val state: MutableStateFlow<List<PackInstance>> = MutableStateFlow(load())

    override fun observe(): Flow<List<PackInstance>> = state.asStateFlow()

    override suspend fun list(): List<PackInstance> = state.value

    override suspend fun get(id: String): PackInstance? = state.value.firstOrNull { it.id == id }

    override suspend fun put(instance: PackInstance) {
        mutex.withLock {
            state.update { current ->
                val replaced = current.map { if (it.id == instance.id) instance else it }
                if (replaced.any { it.id == instance.id }) replaced else replaced + instance
            }
            persist()
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            state.update { current -> current.filterNot { it.id == id } }
            persist()
        }
    }

    private fun load(): List<PackInstance> {
        if (!Files.exists(file)) return emptyList()
        return try {
            val parsed = json.decodeFromString<PacksFile>(Files.readString(file))
            if (parsed.schemaVersion > SCHEMA_VERSION) {
                readOnly = true
                log.warn(
                    "Packs registry at {} is schema_version {} > supported {} -- written by a newer build. " +
                        "Loading read-only; this session will not write it back to avoid clobbering newer data.",
                    file, parsed.schemaVersion, SCHEMA_VERSION,
                )
            }
            parsed.instances
        } catch (e: Exception) {
            log.error("Failed to load packs registry at {} -- starting empty", file, e)
            emptyList()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        if (readOnly) {
            log.debug("Packs registry at {} is from a newer build -- skipping write-back", file)
            return@withContext
        }
        try {
            val snapshot = PacksFile(schemaVersion = SCHEMA_VERSION, instances = state.value)
            AtomicFiles.writeString(file, json.encodeToString(snapshot))
        } catch (e: CancellationException) {
            // Today persist() has no suspension points inside the
            // try-body, so withContext's cancellation check fires at
            // entry/exit and we never land here under cancellation.
            // The next refactor that introduces a suspending serializer
            // OR an async IO call WILL trip cancellation inside the
            // try -- pre-emptive rethrow keeps the structured-
            // concurrency invariant intact across that hypothetical.
            throw e
        } catch (e: Exception) {
            log.error("Failed to persist packs registry at {}", file, e)
        }
    }

    @Serializable
    private data class PacksFile(
        @SerialName("schema_version") val schemaVersion: Int,
        val instances: List<PackInstance>,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
