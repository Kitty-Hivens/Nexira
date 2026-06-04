package hivens.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilerMetricsSerializationTest {

    // Mirrors the launcher's shared Json (di/Modules.kt): tolerant of unknown
    // keys + coerces bad values to defaults.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `parses the exact JSON the agent emits`() {
        // Byte-shape of ProfilerAgent.writeOnce output.
        val raw = """
            {
              "schema_version": 1,
              "liveSetMb": 3210,
              "gcPauseTotalMs": 845,
              "gcCount": 37,
              "sessionMs": 1800000,
              "peakHeapMb": 4096,
              "ranXmxMb": 6144,
              "liveSetReliable": true
            }
        """.trimIndent()
        val m = json.decodeFromString<ProfilerMetrics>(raw)
        assertEquals(1, m.schemaVersion)
        assertEquals(3210, m.liveSetMb)
        assertEquals(845L, m.gcPauseTotalMs)
        assertEquals(37L, m.gcCount)
        assertEquals(6144, m.ranXmxMb)
        assertTrue(m.liveSetReliable)
    }

    @Test
    fun `unreliable no-GC session round-trips`() {
        val raw = """{"schema_version":1,"liveSetMb":0,"gcPauseTotalMs":0,"gcCount":0,"sessionMs":43,"peakHeapMb":0,"ranXmxMb":5940,"liveSetReliable":false}"""
        val m = json.decodeFromString<ProfilerMetrics>(raw)
        assertFalse(m.liveSetReliable)
        assertEquals(0, m.liveSetMb)
    }

    @Test
    fun `old heap profile missing newer fields deserializes with defaults`() {
        val p = json.decodeFromString<HeapProfile>("""{"schema_version":1}""")
        assertEquals(null, p.derivedHeapMb)
        assertTrue(p.recentSamples.isEmpty())
    }

    @Test
    fun `heap profile round-trips an unreliable-but-positive-peak sample`() {
        // foldSample now persists unreliable sessions that still carry a peak; the
        // window must survive encode/decode without dropping that shape.
        val profile = HeapProfile(
            derivedHeapMb = 3005,
            recentSamples = listOf(ProfilerMetrics(liveSetMb = 0, peakHeapMb = 2732, liveSetReliable = false)),
            updatedAtEpoch = 123L,
        )
        val round = json.decodeFromString<HeapProfile>(json.encodeToString(profile))
        assertEquals(profile, round)
        assertEquals(2732, round.recentSamples.single().peakHeapMb)
        assertFalse(round.recentSamples.single().liveSetReliable)
    }
}
