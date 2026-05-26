package hivens.launcher

import hivens.widget.model.LayoutGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * JSON-on-disk store for the widget [LayoutGraph]. Mirrors
 * [JsonPackRepository] in spirit -- single-file envelope with a
 * version tag, atomic write via `<file>.tmp` + ATOMIC_MOVE, malformed-
 * file falls back to the bundled default rather than crashing the
 * launcher.
 *
 * Wire format:
 * ```
 * { "schema_version": N, "graph": { "surfaces": { ... } } }
 * ```
 *
 * The wrapper exists so a schema bump can branch on `schema_version`
 * during load and apply migrations between graph shapes -- see
 * [Migrations].
 */
class LayoutGraphRepository(
    private val file: Path,
    private val json: Json,
    private val defaultGraph: () -> LayoutGraph,
) {

    private val log   = LoggerFactory.getLogger(LayoutGraphRepository::class.java)
    private val mutex = Mutex()
    private val state: MutableStateFlow<LayoutGraph> = MutableStateFlow(load())

    fun observe(): StateFlow<LayoutGraph> = state.asStateFlow()

    fun value(): LayoutGraph = state.value

    /**
     * Read-modify-write under [mutex]; the transform sees the current
     * graph snapshot and returns the replacement. Persistence happens
     * after the in-memory update so a write failure does not strand
     * the observers with an applied-then-rolled-back value.
     */
    suspend fun update(transform: (LayoutGraph) -> LayoutGraph) {
        mutex.withLock {
            val next = transform(state.value)
            if (next == state.value) return@withLock
            state.value = next
            persist()
        }
    }

    private fun load(): LayoutGraph {
        if (!Files.exists(file)) {
            // First run: write the bundled default so subsequent
            // loads operate on the user's editable copy, not the jar
            // resource. Failure to write is non-fatal -- the launcher
            // still runs against the in-memory default.
            val def = defaultGraph()
            try {
                Files.createDirectories(file.parent)
                val envelope = Envelope(schemaVersion = SCHEMA_VERSION, graph = def)
                Files.writeString(file, json.encodeToString(envelope))
            } catch (e: Exception) {
                log.warn("Failed to seed default layout graph at {} -- continuing in memory", file, e)
            }
            return def
        }
        return try {
            val envelope = json.decodeFromString<Envelope>(Files.readString(file))
            Migrations.apply(envelope.schemaVersion, envelope.graph)
        } catch (e: Exception) {
            log.error("Failed to load layout graph at {} -- falling back to bundled default", file, e)
            defaultGraph()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            val envelope = Envelope(schemaVersion = SCHEMA_VERSION, graph = state.value)
            Files.writeString(tmp, json.encodeToString(envelope))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                log.warn(
                    "Filesystem at {} does not support ATOMIC_MOVE; " +
                    "falling back to non-atomic rename for layout-graph.json. " +
                    "Move the data directory to a filesystem that supports " +
                    "atomic rename (ext4, NTFS, APFS) for full durability.",
                    file.parent,
                )
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to persist layout graph at {}", file, e)
        }
    }

    @Serializable
    private data class Envelope(
        @SerialName("schema_version") val schemaVersion: Int,
        val graph: LayoutGraph,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Schema migration ladder. Each step transforms a graph from version
 * N-1 to version N. Kernel-2 ships v1; the ladder is empty until a
 * future kernel-N introduces the first migration -- present so the
 * load path already routes through it.
 */
internal object Migrations {
    fun apply(fromVersion: Int, graph: LayoutGraph): LayoutGraph {
        // Forward-only migration. fromVersion <= current SCHEMA_VERSION
        // is the only supported direction; reading a file written by a
        // newer launcher is treated as "ignore unknown fields, keep what
        // we understand" via the Json instance's `ignoreUnknownKeys`.
        var current = graph
        for (step in fromVersion until CURRENT) {
            current = step(step + 1).apply(current)
        }
        return current
    }

    private const val CURRENT = 1

    private fun step(toVersion: Int): Step = when (toVersion) {
        // Future: 2 -> Step { graph -> ... merge new slots ... }
        else -> Step.IDENTITY
    }

    private fun interface Step {
        fun apply(graph: LayoutGraph): LayoutGraph
        companion object {
            val IDENTITY = Step { it }
        }
    }
}
