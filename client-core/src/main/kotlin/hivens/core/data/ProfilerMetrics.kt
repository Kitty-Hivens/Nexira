package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One game session's heap/GC summary, written by the profiler agent (the
 * `profiler-agent` module) to a JSON file on JVM shutdown and read back by the
 * launcher between runs. Field names + shape MUST match the agent's
 * hand-serialized JSON in `ProfilerAgent.writeOnce`.
 *
 * [liveSetReliable] is false when the session saw no major GC (too short, or a
 * heap so large old-gen never collected) -- the live set is then meaningless and
 * the deriver must ignore the sample.
 */
@Serializable
data class ProfilerMetrics(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val liveSetMb: Int = 0,
    val gcPauseTotalMs: Long = 0,
    val gcCount: Long = 0,
    val sessionMs: Long = 0,
    val peakHeapMb: Int = 0,
    val ranXmxMb: Int = 0,
    val liveSetReliable: Boolean = false,
)

/**
 * Persisted per-(machine, pack) heap profile: the heap the launcher last derived
 * plus a short window of recent reliable samples it derived from. Stored next to
 * the instance so it follows that instance's lifetime.
 */
@Serializable
data class HeapProfile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val derivedHeapMb: Int? = null,
    val recentSamples: List<ProfilerMetrics> = emptyList(),
    val updatedAtEpoch: Long = 0,
)
