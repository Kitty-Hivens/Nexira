package hivens.core.jvm

/**
 * The machine-aware baseline heap (`-Xmx`, MB) for an instance that is NOT pinned to
 * an explicit value. Two roles:
 *  - the static heap when the adaptive sizer is off (the "Automatic" tier), and
 *  - the cold-start the adaptive sizer grows FROM before it has any session data.
 *
 * Pure: a function of host RAM only, no I/O -- unit-testable like [HeapDeriver]. There
 * is no pack-declared RAM hint in the manifest today, so host RAM is the only input.
 *
 * [TARGET_FRACTION] aims generously (modded MC wants a comfortable heap), bounded by:
 *  - [UPPER_BOUND_MB]: a 64 GB box gains nothing from a 40 GB heap -- only longer GC
 *    pauses -- so cap what a modded client can actually use.
 *  - [CEILING_FRACTION]: never claim more than 75% of host RAM (leave the OS and the
 *    rest of the JVM room); the same ceiling [HeapDeriver] clamps to.
 *  - [FLOOR_MB]: modded clients need at least ~1 GB. This floor can intentionally
 *    exceed [CEILING_FRACTION] on a sub-1.4 GB host (which cannot really run modded MC
 *    anyway) and also covers [SystemMemory]'s 16 GB fallback and a degenerate 0 read.
 */
object AutomaticHeap {

    const val FLOOR_MB = 1024
    const val CEILING_FRACTION = 0.75   // never starve the host; matches HeapDeriver's ceiling
    const val TARGET_FRACTION = 0.60    // modded-generous baseline
    const val UPPER_BOUND_MB = 10240    // 10 GB: a huge machine does not get a huge heap

    /** Baseline heap (MB) derived from host RAM. Deterministic; clamped to a sane band. */
    fun compute(systemRamMb: Int): Int =
        (systemRamMb * TARGET_FRACTION).toInt()
            .coerceAtMost(UPPER_BOUND_MB)
            .coerceAtMost((systemRamMb * CEILING_FRACTION).toInt())
            .coerceAtLeast(FLOOR_MB)
}
