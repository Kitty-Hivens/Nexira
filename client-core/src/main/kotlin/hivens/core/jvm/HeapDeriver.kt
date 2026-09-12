package hivens.core.jvm

import hivens.core.data.ProfilerMetrics

/**
 * Derives the heap (`-Xmx`, MB) for the NEXT launch from observed metrics. Pure:
 * no I/O, no clocks -- unit-testable like the editor's geometry helpers.
 *
 * The heap must cover BOTH the steady-state live set (with [DEFAULT_HEADROOM] for
 * allocation churn) AND the observed peak high-water (with a thinner
 * [DEFAULT_PEAK_HEADROOM]) -- whichever is larger -- so it never lands below a
 * heap the session actually reached. Real data motivated the peak term:
 * `liveSet * 1.5` alone can fall just under the observed peak on packs whose churn
 * is heavy relative to the retained set. Result is clamped to `[floorMb, ram*0.75]`.
 */
object HeapDeriver {

    const val DEFAULT_HEADROOM = 1.5       // multiplier over the live set
    const val DEFAULT_PEAK_HEADROOM = 1.1  // multiplier over the observed peak

    /**
     * How long a session must last to count as a measurement. Well short of a
     * play session and well past a startup that fell over: the runs this exists
     * to reject ended in seconds.
     */
    const val MIN_SESSION_MS = 60_000L

    /**
     * Single-sample derive. [liveSetMb] is the post-major-GC retained heap (pass 0
     * when no reliable live set was observed -- e.g. a concurrent collector that
     * never reports a major cycle); [peakHeapMb] is the high-water usage, valid
     * even without a major GC. The larger of the two headroomed terms wins.
     */
    fun derive(
        liveSetMb: Int,
        peakHeapMb: Int,
        machineRamMb: Int,
        floorMb: Int,
        headroom: Double = DEFAULT_HEADROOM,
        peakHeadroom: Double = DEFAULT_PEAK_HEADROOM,
    ): Int {
        // A nonsensical machine read (e.g. a 0 fallback) must not yield a ceiling
        // below the floor -- pull the ceiling up to the floor in that case.
        val ceil = maxOf((machineRamMb * 0.75).toInt(), floorMb)
        val want = maxOf((liveSetMb * headroom).toInt(), (peakHeapMb * peakHeadroom).toInt())
        return want.coerceIn(floorMb, ceil)
    }

    /**
     * Rolling derive over recent sessions: the largest RELIABLE live set and the
     * largest peak across the window, so one light session can't shrink the heap
     * below what a heavier one needed. The live-set term counts only sessions with
     * `liveSetReliable = true`; the peak term counts every session -- peak is valid
     * even when no major GC let the live set settle (ZGC / Shenandoah). Returns null
     * only when neither signal exists (caller then keeps the current heap).
     */
    fun derive(
        recent: List<ProfilerMetrics>,
        machineRamMb: Int,
        floorMb: Int,
        current: Int? = null,
        headroom: Double = DEFAULT_HEADROOM,
        peakHeadroom: Double = DEFAULT_PEAK_HEADROOM,
    ): Int? {
        val maxLive = recent.filter { it.liveSetReliable && it.liveSetMb > 0 }.maxOfOrNull { it.liveSetMb } ?: 0
        val maxPeak = recent.filter { it.peakHeapMb > 0 }.maxOfOrNull { it.peakHeapMb } ?: 0
        if (maxLive == 0 && maxPeak == 0) return null
        val want = derive(maxLive, maxPeak, machineRamMb, floorMb, headroom, peakHeadroom)
        // Peak alone is a floor under the demand, not a measure of it: a heap
        // roomy enough never fills, so the collector never runs, the live set is
        // never established and the high-water mark sits far below what the pack
        // would use if it were asked. Reading that as the answer walks the heap
        // down every session, and the smaller it gets the more it looks justified.
        // Without one reliable live set in the window the heap may rise, never
        // fall.
        val measured = recent.any { it.liveSetReliable && it.liveSetMb > 0 }
        return if (measured || current == null) want else maxOf(want, current)
    }

    /**
     * Rolls [last] into the [recent] window, keeping the newest [window] samples --
     * but only if [last] carries signal (a reliable live set OR a positive peak). A
     * zero-signal record (no major GC AND peak 0, e.g. a near-instant crash) is
     * dropped: appending it would let a run of such records evict the good samples via
     * `takeLast` and collapse the derived heap back to the static base. [derive] still
     * filters reliability per term; this guards what is allowed into the window.
     */
    fun foldSample(
        recent: List<ProfilerMetrics>,
        last: ProfilerMetrics?,
        window: Int,
    ): List<ProfilerMetrics> {
        val keep = window.coerceAtLeast(0)
        if (last == null || !carriesSignal(last)) return recent.takeLast(keep)
        return (recent + last).takeLast(keep)
    }

    /**
     * Whether a session says anything about what the pack needs.
     *
     * A record with no major GC and no peak is the obvious nothing. A session
     * that ended in the first seconds is the less obvious one: it allocated, it
     * collected, it has a peak, and every number in it describes a startup that
     * never reached a world. Those arrive in runs -- a crash loop, or a launch
     * the content guard keeps ending -- and a window of them is enough to put the
     * heap on the floor and keep it there, because the starved sessions that
     * follow then report a peak pinned to the ceiling they were given.
     */
    private fun carriesSignal(m: ProfilerMetrics): Boolean =
        (m.liveSetReliable || m.peakHeapMb > 0) && m.sessionMs >= MIN_SESSION_MS
}
