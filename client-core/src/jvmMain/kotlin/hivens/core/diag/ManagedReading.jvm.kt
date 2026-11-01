package hivens.core.diag

import com.sun.management.HotSpotDiagnosticMXBean
import org.slf4j.LoggerFactory
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import javax.management.ObjectName
import hivens.core.diag.MemoryReport.Collector
import hivens.core.diag.MemoryReport.Region

private val log = LoggerFactory.getLogger("MemoryReport")

internal actual fun readManaged(withNativeCategories: Boolean): ManagedReading {
    val mem = ManagementFactory.getMemoryMXBean()
    val heapUsage = mem.heapMemoryUsage
    return ManagedReading(
        heap = Region(
            name = "heap",
            usedMb = heapUsage.used.toMb(),
            committedMb = heapUsage.committed.toMb(),
            maxMb = heapUsage.max.takeIf { it > 0 }?.toMb(),
        ),
        pools = nonHeapPools(),
        collectors = ManagementFactory.getGarbageCollectorMXBeans()
            .map { Collector(it.name, it.collectionCount.coerceAtLeast(0), it.collectionTime.coerceAtLeast(0)) },
        threads = ManagementFactory.getThreadMXBean().threadCount,
        classesLoaded = ManagementFactory.getClassLoadingMXBean().loadedClassCount.toLong(),
        directBufferMb = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
            .sumOf { it.memoryUsed.coerceAtLeast(0) }.toMb(),
        uptimeMs = ManagementFactory.getRuntimeMXBean().uptime,
        ergonomics = readErgonomics(),
        nativeMemory = if (withNativeCategories) readNativeMemory() else null,
    )
}

internal actual fun requestGarbageCollection() = ManagementFactory.getMemoryMXBean().gc()

internal actual fun totalGcPauseMs(): Long =
    ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }

/**
 * Non-heap pools, with the four code-cache segments folded into one entry.
 * Their split is a JIT implementation detail and five near-empty rows push
 * the numbers that matter off the line.
 */
private fun nonHeapPools(): List<Region> {
    val pools = ManagementFactory.getMemoryPoolMXBeans()
        .filter { it.type == MemoryType.NON_HEAP }
    val (codeHeaps, rest) = pools.partition { it.name.startsWith("CodeHeap") }
    val folded = buildList {
        if (codeHeaps.isNotEmpty()) {
            add(
                Region(
                    name = "code cache",
                    usedMb = codeHeaps.sumOf { it.usage?.used ?: 0 }.toMb(),
                    committedMb = codeHeaps.sumOf { it.usage?.committed ?: 0 }.toMb(),
                ),
            )
        }
        rest.forEach { pool ->
            // Documented to be null for a pool that is no longer valid.
            val usage = pool.usage ?: return@forEach
            add(Region(pool.name, usage.used.toMb(), usage.committed.toMb(), usage.max.takeIf { it > 0 }?.toMb()))
        }
    }
    return folded.filter { it.committedMb > 0 }
}

/**
 * Native memory tracking through the diagnostic-command bean, which is the
 * same VM.native_memory that jcmd invokes from outside. Returns null when the
 * bean is absent (no jdk.management in the image) and the tracker's own
 * refusal string when the flag was not passed at launch, which
 * [parseNativeMemorySummary] then yields nothing for.
 */
private fun readNativeMemory(): String? = runCatching {
    val tracking = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
        ?.let { runCatching { it.getVMOption("NativeMemoryTracking").value }.getOrNull() }
    if (tracking == null || tracking == "off") return@runCatching null
    val server = ManagementFactory.getPlatformMBeanServer()
    server.invoke(
        ObjectName("com.sun.management:type=DiagnosticCommand"),
        "vmNativeMemory",
        arrayOf(arrayOf("summary")),
        arrayOf(Array<String>::class.java.name),
    ) as? String
}.getOrElse {
    log.debug("native memory tracking unavailable", it)
    null
}

/**
 * The handful of flags whose values are a function of the host rather than of
 * the build. Read by name because the bean has no listing that separates
 * ergonomic values from the hundreds of defaults nobody typed.
 */
private fun readErgonomics(): List<Pair<String, String>> = runCatching {
    val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java) ?: return emptyList()
    ERGONOMIC_FLAGS.mapNotNull { flag ->
        runCatching { bean.getVMOption(flag) }.getOrNull()?.let { option ->
            val shown = if (flag.endsWith("Size") || flag == "MaxHeapSize") {
                option.value.toLongOrNull()?.let { "${it.toMb()} MB" } ?: option.value
            } else {
                option.value
            }
            flag to shown
        }
    }
}.getOrElse {
    log.debug("ergonomics read failed", it)
    emptyList()
}

private val ERGONOMIC_FLAGS = listOf(
    "MaxHeapSize",
    "InitialHeapSize",
    "MinHeapFreeRatio",
    "MaxHeapFreeRatio",
    "ParallelGCThreads",
    "ConcGCThreads",
    "G1PeriodicGCInterval",
    "G1HeapRegionSize",
    "UseCompressedOops",
)
