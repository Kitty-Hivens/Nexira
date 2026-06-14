package hivens.core.jvm

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Host CPU + swap readouts for the diagnostics ("About") screen. Pure JVM, no
 * native dependency: rich on Linux via /proc + /sys (plain file reads), with a
 * graceful JVM-bean fallback elsewhere.
 *
 * Deliberately NOT OSHI: its default core pulls JNA, which this project dropped
 * for Project Panama (libtray). OSHI's FFM module would be the cross-platform
 * path, but as of mid-2026 it is unpublished, dormant, and has no CPU/memory
 * implementation -- so swap it in behind this object if it ever matures.
 *
 * Memoized -- host topology is constant per process.
 */
object SystemHardware {

    private val logger = LoggerFactory.getLogger("SystemHardware")

    /**
     * [physicalCores] is null when the platform does not expose it (non-Linux, or
     * a /proc without topology fields -- some VMs). [logicalThreads] is always the
     * JVM's view. Frequencies are MHz, null when /sys cpufreq is absent.
     */
    data class CpuInfo(
        val physicalCores: Int?,
        val logicalThreads: Int,
        val minMhz: Int?,
        val maxMhz: Int?,
    )

    private val logical = Runtime.getRuntime().availableProcessors()

    val cpu: CpuInfo by lazy { readCpu() }

    /** Total swap (MB), incl. zram-backed swap (it appears in /proc/swaps). Null off Linux / no swap. */
    val swapTotalMb: Int? by lazy { readSwap() }

    private fun readCpu(): CpuInfo {
        val cores = runCatching {
            Path.of("/proc/cpuinfo").takeIf { it.exists() }?.readText()?.let(::physicalCoresFromCpuinfo)
        }.getOrElse { logger.warn("cpuinfo read failed", it); null }
        val (min, max) = runCatching { readCpuFreqMhz() }.getOrElse { null to null }
        return CpuInfo(cores, logical, min, max)
    }

    private fun readSwap(): Int? = runCatching {
        Path.of("/proc/swaps").takeIf { it.exists() }?.readText()?.let(::swapTotalMbFromProcSwaps)
    }.getOrElse { logger.warn("swaps read failed", it); null }

    private fun readCpuFreqMhz(): Pair<Int?, Int?> {
        val dir = Path.of("/sys/devices/system/cpu/cpu0/cpufreq")
        fun mhz(name: String): Int? =
            dir.resolve(name).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.div(1000)
        return mhz("cpuinfo_min_freq") to mhz("cpuinfo_max_freq")
    }

    // ── Pure parsers (internal for tests) ──────────────────────────────────────

    /**
     * Physical core count from /proc/cpuinfo: the count of distinct
     * (physical id, core id) pairs. Falls back to the per-socket "cpu cores"
     * value when those fields are absent (some kernels / VMs), else null.
     */
    internal fun physicalCoresFromCpuinfo(text: String): Int? {
        val pairs = HashSet<Pair<Int, Int>>()
        var phys: Int? = null
        var core: Int? = null
        var cpuCores: Int? = null
        fun flush() {
            val p = phys
            val c = core
            if (p != null && c != null) pairs += p to c
            phys = null
            core = null
        }
        for (line in text.lineSequence()) {
            when {
                line.startsWith("physical id") -> phys = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith("core id")     -> core = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith("cpu cores")   -> cpuCores = cpuCores ?: line.substringAfter(':').trim().toIntOrNull()
                line.isBlank()                 -> flush()
            }
        }
        flush()
        return pairs.size.takeIf { it > 0 } ?: cpuCores?.takeIf { it > 0 }
    }

    /** Total swap MB from /proc/swaps (sums all rows, incl. zram). The Size column is KB. */
    internal fun swapTotalMbFromProcSwaps(text: String): Int? =
        text.lineSequence()
            .drop(1) // header row
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(2)?.toLongOrNull() }
            .sum()
            .takeIf { it > 0 }
            ?.let { (it / 1024).toInt() }
}
