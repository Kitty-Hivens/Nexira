package hivens.core.jvm

import hivens.core.data.ProfilerMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeapDeriverTest {

    @Test
    fun `live-set term drives when it exceeds the peak term`() {
        // 2048*1.5 = 3072 vs 1000*1.1 = 1100 -> 3072, within [1024, 16384*0.75]
        assertEquals(3072, HeapDeriver.derive(liveSetMb = 2048, peakHeapMb = 1000, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `peak term drives when churn is heavy relative to the live set`() {
        // Real TechnoMagic session: live 3666, peak 5164.
        // liveSet*1.5 = 5499 lands just above peak; peak*1.1 = 5680 is the safer floor.
        assertEquals(5680, HeapDeriver.derive(liveSetMb = 3666, peakHeapMb = 5164, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `peak alone derives when the live set is unreliable (zero)`() {
        // Concurrent collector never reported a major GC -> liveSet 0, peak still valid.
        assertEquals(5500, HeapDeriver.derive(liveSetMb = 0, peakHeapMb = 5000, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `clamps up to the floor when both terms are tiny`() {
        assertEquals(1024, HeapDeriver.derive(liveSetMb = 200, peakHeapMb = 300, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `clamps down to 75 percent of machine RAM`() {
        // max(8000*1.5, 9000*1.1) = 12000 would exceed 8192*0.75 = 6144
        assertEquals(6144, HeapDeriver.derive(liveSetMb = 8000, peakHeapMb = 9000, machineRamMb = 8192, floorMb = 1024))
    }

    @Test
    fun `degenerate machine RAM never yields below the floor`() {
        assertEquals(1024, HeapDeriver.derive(liveSetMb = 4000, peakHeapMb = 4000, machineRamMb = 0, floorMb = 1024))
    }

    @Test
    fun `rolling derive takes the largest reliable live set and the largest peak`() {
        val recent = listOf(
            ProfilerMetrics(liveSetMb = 3000, peakHeapMb = 4000, liveSetReliable = true),
            ProfilerMetrics(liveSetMb = 0,    peakHeapMb = 5000, liveSetReliable = false), // unreliable: peak still counts
            ProfilerMetrics(liveSetMb = 1200, peakHeapMb = 1500, liveSetReliable = true),
        )
        // maxLive(reliable) = 3000 -> *1.5 = 4500; maxPeak(any) = 5000 -> *1.1 = 5500 -> 5500 wins
        assertEquals(5500, HeapDeriver.derive(recent, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `rolling derive ignores an unreliable session's live set but keeps its peak`() {
        val recent = listOf(
            ProfilerMetrics(liveSetMb = 9000, peakHeapMb = 4000, liveSetReliable = false), // bogus live, ignored
        )
        // live term skipped (unreliable); peak 4000*1.1 = 4400
        assertEquals(4400, HeapDeriver.derive(recent, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `rolling derive returns null when there is no signal at all`() {
        assertNull(HeapDeriver.derive(emptyList(), machineRamMb = 16384, floorMb = 1024))
        assertNull(
            HeapDeriver.derive(
                listOf(ProfilerMetrics(liveSetMb = 0, peakHeapMb = 0, liveSetReliable = false)),
                machineRamMb = 16384, floorMb = 1024,
            ),
        )
    }

    @Test
    fun `foldSample keeps an unreliable session that still has a positive peak`() {
        // ChoKO's first file: no major GC -> liveSet 0 / unreliable, but peak 2732 is real.
        val prior = listOf(ProfilerMetrics(liveSetMb = 1000, peakHeapMb = 1500, liveSetReliable = true))
        val zeroLivePositivePeak = ProfilerMetrics(liveSetMb = 0, peakHeapMb = 2732, liveSetReliable = false)
        assertEquals(prior + zeroLivePositivePeak, HeapDeriver.foldSample(prior, zeroLivePositivePeak, 5))
    }

    @Test
    fun `foldSample drops a zero-signal session so it cannot evict good samples`() {
        // No GC AND peak 0 (near-instant crash): nothing to learn -> must not enter the
        // window, or a run of them would push the good samples out and collapse the heap.
        val good = listOf(
            ProfilerMetrics(liveSetMb = 3000, peakHeapMb = 4000, liveSetReliable = true),
            ProfilerMetrics(liveSetMb = 0,    peakHeapMb = 5000, liveSetReliable = false),
        )
        val zeroSignal = ProfilerMetrics(liveSetMb = 0, peakHeapMb = 0, liveSetReliable = false)
        assertEquals(good, HeapDeriver.foldSample(good, zeroSignal, 5))
    }

    @Test
    fun `foldSample on a null session leaves the window unchanged`() {
        val good = listOf(ProfilerMetrics(liveSetMb = 3000, peakHeapMb = 4000, liveSetReliable = true))
        assertEquals(good, HeapDeriver.foldSample(good, null, 5))
    }

    @Test
    fun `foldSample evicts the oldest sample when the window is full`() {
        val recent = (1..5).map { ProfilerMetrics(liveSetMb = it * 100, peakHeapMb = it * 100, liveSetReliable = true) }
        val newest = ProfilerMetrics(liveSetMb = 9000, peakHeapMb = 9000, liveSetReliable = true)
        val folded = HeapDeriver.foldSample(recent, newest, 5)
        assertEquals(5, folded.size)
        assertEquals(recent.drop(1) + newest, folded)
    }

    @Test
    fun `derive floors the headroomed term instead of rounding up`() {
        // 1001 * 1.5 = 1501.5 -> truncates to 1501; guards against a switch to rounding.
        assertEquals(1501, HeapDeriver.derive(liveSetMb = 1001, peakHeapMb = 0, machineRamMb = 16384, floorMb = 1024))
    }

    @Test
    fun `rolling derive returns null when the only live set is unreliable and there is no peak`() {
        // liveSetMb > 0 but unreliable, peak 0 -> no usable signal -> null. Guards the
        // reliability filter: a plain liveSetMb>0 check would wrongly derive 9000*1.5.
        assertNull(
            HeapDeriver.derive(
                listOf(ProfilerMetrics(liveSetMb = 9000, peakHeapMb = 0, liveSetReliable = false)),
                machineRamMb = 16384, floorMb = 1024,
            ),
        )
    }
}
