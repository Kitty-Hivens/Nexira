package hivens.launcher

import hivens.config.Storage
import hivens.core.data.HeapProfile
import hivens.core.data.ProfilerMetrics
import hivens.core.io.AtomicFiles
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the profiler agent's per-session metrics and reads/writes the persisted
 * per-(machine, instance) [HeapProfile]. Both files live in the instance's own
 * working directory -- the same dir the game runs in -- so they follow that
 * instance's lifetime and never leak across machines.
 *
 * Dir-agnostic on purpose: the caller knows whether a launch is a pack instance
 * (`instances/<dir>/`) or an SC server (`clients/<assetDir>/`) and passes the
 * right directory.
 */
class ProfilerProfileStore(private val json: Json) {

    private val log = LoggerFactory.getLogger(ProfilerProfileStore::class.java)

    /** Path the agent should write this launch's session metrics to. */
    fun metricsPath(instanceDir: Path): Path = instanceDir.resolve(Storage.PROFILER_METRICS_FILE)

    /** The previous session's metrics the agent left behind, or null if none/unreadable. */
    fun readMetrics(instanceDir: Path): ProfilerMetrics? {
        val file = metricsPath(instanceDir)
        if (!Files.exists(file)) return null
        return try {
            json.decodeFromString<ProfilerMetrics>(Files.readString(file))
        } catch (e: Exception) {
            log.warn("Unreadable profiler metrics at {}; ignoring", file, e)
            null
        }
    }

    /** The persisted heap profile for [instanceDir], or null if none/unreadable. */
    fun readProfile(instanceDir: Path): HeapProfile? {
        val file = instanceDir.resolve(Storage.HEAP_PROFILE_FILE)
        if (!Files.exists(file)) return null
        return try {
            json.decodeFromString<HeapProfile>(Files.readString(file))
        } catch (e: Exception) {
            log.warn("Unreadable heap profile at {}; starting fresh", file, e)
            null
        }
    }

    fun writeProfile(instanceDir: Path, profile: HeapProfile) {
        val file = instanceDir.resolve(Storage.HEAP_PROFILE_FILE)
        try {
            AtomicFiles.writeString(file, json.encodeToString(profile))
        } catch (e: Exception) {
            log.error("Failed to persist heap profile at {}", file, e)
        }
    }

    /** Consumes the agent's metrics file so a session is folded exactly once. */
    fun deleteMetrics(instanceDir: Path) {
        runCatching { Files.deleteIfExists(metricsPath(instanceDir)) }
    }

    companion object {
        /**
         * How many recent samples to keep in the rolling window. Each carries a
         * reliable live set OR a positive peak (`HeapDeriver.foldSample` admits
         * them; the deriver filters reliability per term).
         */
        const val SAMPLE_WINDOW = 5
    }
}
