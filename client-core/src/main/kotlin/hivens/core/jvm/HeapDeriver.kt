package hivens.core.jvm

/**
 * Derives the heap (`-Xmx`, MB) for the NEXT launch from observed live-set
 * measurements. Pure: no I/O, no clocks -- unit-testable like the editor's
 * geometry helpers.
 *
 * Live set * [headroom] gives allocation room above the steady-state retained
 * size; the result is clamped to `[floorMb, machineRamMb * 0.75]` so it never
 * drops below a usable floor nor starves the OS / GPU of RAM.
 */
object HeapDeriver {

    const val DEFAULT_HEADROOM = 1.5

    /** Single-sample derive. [liveSetMb] is the post-major-GC retained heap. */
    fun derive(
        liveSetMb: Int,
        machineRamMb: Int,
        floorMb: Int,
        headroom: Double = DEFAULT_HEADROOM,
    ): Int {
        // A nonsensical machine read (e.g. a 0 fallback) must not yield a ceiling
        // below the floor -- pull the ceiling up to the floor in that case.
        val ceil = maxOf((machineRamMb * 0.75).toInt(), floorMb)
        val want = (liveSetMb * headroom).toInt()
        return want.coerceIn(floorMb, ceil)
    }

    /**
     * Rolling-max derive over recent reliable samples: take the largest observed
     * live set so a single GC-light session can't shrink the heap below what a
     * heavier session needed. Empty / all-non-positive input -> null, signalling
     * the caller to keep the current heap (no reliable data yet).
     */
    fun derive(
        recentLiveSetsMb: List<Int>,
        machineRamMb: Int,
        floorMb: Int,
        headroom: Double = DEFAULT_HEADROOM,
    ): Int? {
        val peak = recentLiveSetsMb.filter { it > 0 }.maxOrNull() ?: return null
        return derive(peak, machineRamMb, floorMb, headroom)
    }
}
