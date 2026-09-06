package hivens.core.diag

import com.sun.management.HotSpotDiagnosticMXBean
import hivens.core.jvm.SystemHardware
import hivens.core.jvm.SystemMemory
import org.slf4j.LoggerFactory
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Path
import javax.management.ObjectName
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * What this process actually costs, assembled from inside it.
 *
 * The numbers a launcher is judged on are not the ones the JVM volunteers.
 * Committed heap says nothing about what survives a collection, and neither
 * says anything about the class archive, the GL stack or the allocator arena,
 * which together outweigh the heap on an idle session. So this reads four
 * sources and puts them in one place: the management beans for the heap, the
 * pools and the collectors, `/proc/self/smaps` for what is actually resident
 * and where it went, native memory tracking for the JVM's own categories when
 * it was enabled at launch, and [HotSpotDiagnosticMXBean] for the values
 * ergonomics picked on this particular machine.
 *
 * The last one is the point of shipping it rather than measuring once on a
 * developer box: heap ceiling, collector thread counts and region size are all
 * functions of the host's RAM and core count, so the same build behaves
 * differently on every machine it runs on, and the only honest way to know is
 * to ask it there.
 *
 * Everything degrades instead of failing. Off Linux there is no smaps and the
 * mapping breakdown is empty. On a runtime image without `jdk.management` the
 * ergonomics and the native categories are absent. The heap and pool numbers
 * come from `java.management` and are always there.
 */
object MemoryReport {

    private val log = LoggerFactory.getLogger("MemoryReport")

    /** One memory pool or mapping group, in MB. [maxMb] is null when unbounded. */
    data class Region(val name: String, val usedMb: Long, val committedMb: Long = usedMb, val maxMb: Long? = null)

    /** One collector's totals since process start. */
    data class Collector(val name: String, val collections: Long, val totalPauseMs: Long)

    data class Snapshot(
        val heap: Region,
        val pools: List<Region>,
        val collectors: List<Collector>,
        val mappings: List<Region>,
        val nativeCategories: List<Region>,
        val ergonomics: List<Pair<String, String>>,
        val rssMb: Long?,
        val pssMb: Long?,
        val threads: Int,
        val classesLoaded: Long,
        val directBufferMb: Long,
        val uptimeMs: Long,
        val hostRamMb: Int?,
        val cpuThreads: Int,
        val cpuCores: Int?,
    ) {
        /** Resident anonymous memory: the heap, the code cache, the collector's structures. */
        val anonymousMb: Long
            get() = mappings.filter { it.name == ANONYMOUS || it.name == MALLOC_ARENA }.sumOf { it.usedMb }

        /**
         * Resident file-backed memory: the class archive and the libraries. It
         * counts towards the process size, but it is a cache of files on disk,
         * shared with anything else mapping them, and the kernel drops it under
         * pressure instead of swapping it. Worth separating from the rest before
         * anyone reads the total as pressure.
         */
        val fileBackedMb: Long
            get() = mappings.filter { it.name != ANONYMOUS && it.name != MALLOC_ARENA && it.name != "kernel" }
                .sumOf { it.usedMb }
    }

    /**
     * [withNativeCategories] pulls the JVM's own native categories, which means
     * asking the diagnostic-command bean, which means building the platform
     * MBean server if nothing else has. That registers every platform MXBean and
     * costs the process a few permanent megabytes, so a report about memory does
     * not do it unless the categories are actually going to be shown.
     */
    fun collect(withNativeCategories: Boolean = false): Snapshot {
        val mem = ManagementFactory.getMemoryMXBean()
        val heapUsage = mem.heapMemoryUsage
        val runtime = ManagementFactory.getRuntimeMXBean()
        val smaps = readSmaps()
        return Snapshot(
            heap = Region(
                name = "heap",
                usedMb = heapUsage.used.toMb(),
                committedMb = heapUsage.committed.toMb(),
                maxMb = heapUsage.max.takeIf { it > 0 }?.toMb(),
            ),
            pools = nonHeapPools(),
            collectors = ManagementFactory.getGarbageCollectorMXBeans()
                .map { Collector(it.name, it.collectionCount.coerceAtLeast(0), it.collectionTime.coerceAtLeast(0)) },
            mappings = smaps?.let(::groupSmaps).orEmpty(),
            nativeCategories = if (withNativeCategories) {
                readNativeMemory()?.let(::parseNativeMemorySummary).orEmpty()
            } else {
                emptyList()
            },
            ergonomics = readErgonomics(),
            rssMb = smaps?.let { sumSmapsField(it, "Rss:") },
            pssMb = smaps?.let { sumSmapsField(it, "Pss:") },
            threads = ManagementFactory.getThreadMXBean().threadCount,
            classesLoaded = ManagementFactory.getClassLoadingMXBean().loadedClassCount.toLong(),
            directBufferMb = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
                .sumOf { it.memoryUsed.coerceAtLeast(0) }.toMb(),
            uptimeMs = runtime.uptime,
            hostRamMb = SystemMemory.totalPhysicalMbOrNull(),
            cpuThreads = SystemHardware.cpu.logicalThreads,
            cpuCores = SystemHardware.cpu.physicalCores,
        )
    }

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
     * Collect, then report what survived. The pause is measured from the
     * collectors' own counters rather than the wall clock, so it is the time the
     * application was actually stopped and not the time the request took.
     */
    fun collectAfterGc(): Triple<Snapshot, Snapshot, Long> {
        val before = collect()
        val pauseBefore = totalPauseMs()
        ManagementFactory.getMemoryMXBean().gc()
        val pause = totalPauseMs() - pauseBefore
        return Triple(before, collect(), pause)
    }

    private fun totalPauseMs(): Long =
        ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * The short report: what it costs now, and what the host talked the JVM into.
     * [withHint] is false when the long report follows, which already contains
     * everything the hint would point at.
     */
    fun summaryLines(s: Snapshot, withHint: Boolean = true): List<String> = buildList {
        add("memory on this host")
        add("  host       ${s.hostRamMb?.let { "$it MB RAM" } ?: "RAM unknown"}, " +
            "${s.cpuThreads} threads${s.cpuCores?.let { " ($it cores)" } ?: ""}")
        add("  process    ${s.rssMb?.let { "resident $it MB" } ?: "resident unknown"}" +
            "${s.pssMb?.let { ", proportional $it MB" }.orEmpty()}, " +
            "${s.threads} threads, up ${formatDuration(s.uptimeMs)}")
        add("  heap       used ${s.heap.usedMb} MB, committed ${s.heap.committedMb} MB" +
            "${s.heap.maxMb?.let { ", ceiling $it MB" }.orEmpty()}")
        val pools = s.pools.joinToString(", ") { "${it.name.lowercase()} ${it.committedMb} MB" }
        if (pools.isNotEmpty()) add("  off heap   $pools, direct buffers ${s.directBufferMb} MB")
        if (s.mappings.isNotEmpty()) {
            add("  resident   anonymous ${s.anonymousMb} MB, file backed ${s.fileBackedMb} MB " +
                "(class archive and libraries, evictable)")
        }
        add("  classes    ${s.classesLoaded} loaded")
        s.collectors.forEach { add("  gc         ${it.name}: ${it.collections} collections, ${it.totalPauseMs} ms stopped") }
        if (withHint) add("  more       \"mem full\" for the breakdown, \"mem gc\" for the live set")
    }

    /** The long report: where every resident megabyte is, and which knobs are in force. */
    fun fullLines(s: Snapshot): List<String> = buildList {
        addAll(summaryLines(s, withHint = false))
        if (s.mappings.isNotEmpty()) {
            add("")
            add("resident breakdown")
            s.mappings.sortedByDescending { it.usedMb }.forEach {
                add("  ${it.name.padEnd(26)} ${it.usedMb} MB")
            }
            add("  the anonymous bucket is split by the jvm below, not by the kernel")
        }
        if (s.nativeCategories.isNotEmpty()) {
            add("")
            add("jvm native categories (committed, which is what was taken, not what is resident)")
            s.nativeCategories.sortedByDescending { it.committedMb }.take(12).forEach {
                add("  ${it.name.padEnd(26)} ${it.committedMb} MB")
            }
        } else {
            add("")
            add("jvm native categories unavailable: start with -XX:NativeMemoryTracking=summary to see them")
        }
        if (s.ergonomics.isNotEmpty()) {
            add("")
            add("what ergonomics chose for this host")
            s.ergonomics.forEach { (name, value) -> add("  ${name.padEnd(22)} $value") }
        }
    }

    /** Duration in the shortest form that still reads as a duration. */
    internal fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val sec = totalSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else  -> "${sec}s"
        }
    }

    // ── Sources ──────────────────────────────────────────────────────────────

    private fun readSmaps(): String? = runCatching {
        Path.of("/proc/self/smaps").takeIf { it.exists() }?.readText()
    }.getOrElse {
        log.debug("smaps read failed", it)
        null
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

    // ── Pure parsers (internal for tests) ────────────────────────────────────

    internal const val MALLOC_ARENA = "allocator arena"

    /**
     * Everything the kernel reports as anonymous, in one bucket. The Java heap,
     * the metaspace, the code cache and the collector's structures are all in
     * here and none of them is separable by name or address: the heap moves with
     * the ceiling and compressed references, and the class space is mapped next
     * to it. The split that follows in the report comes from the JVM itself,
     * which does not have to guess.
     */
    internal const val ANONYMOUS = "anonymous (heap, code, gc)"

    /**
     * Group `/proc/self/smaps` regions into the handful of buckets worth naming.
     * Only what the kernel names can be named: the archive, the libraries and the
     * allocator's break region. Everything anonymous goes into one bucket,
     * because the heap, the metaspace and the code cache are indistinguishable
     * from each other there, and the JVM splits them for us further down the
     * report.
     */
    internal fun groupSmaps(text: String): List<Region> {
        val totals = LinkedHashMap<String, Long>()
        var bucket: String? = null
        for (line in text.lineSequence()) {
            val header = MAP_HEADER.matchEntire(line)
            if (header != null) {
                bucket = bucketFor(header.groupValues[1].trim())
                continue
            }
            if (line.startsWith("Rss:")) {
                val kb = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: continue
                val key = bucket ?: continue
                totals[key] = (totals[key] ?: 0) + kb
            }
        }
        return totals.entries
            .map { Region(it.key, it.value / 1024) }
            .filter { it.usedMb > 0 }
    }

    private fun bucketFor(name: String): String = when {
        name.endsWith(".jsa")                        -> "class archive"
        name.endsWith(".jar")                        -> "jar"
        // On the file name only. The path carries the AppImage mount point and the
        // user's home directory, and someone whose home is /home/mesa would
        // otherwise have every library in the report filed under the gpu stack.
        fileName(name).contains("skiko")             -> "skia"
        GL_LIB.containsMatchIn(fileName(name))       -> "gpu stack"
        fileName(name).contains("libjvm")            -> "jvm library"
        name.endsWith(".so") || name.contains(".so.") -> "other libraries"
        name == "[heap]"                             -> MALLOC_ARENA
        name.isEmpty()                               -> ANONYMOUS
        name.startsWith("[")                         -> "kernel"
        else                                         -> "other files"
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    /** Sum one smaps field over every region, in MB. */
    internal fun sumSmapsField(text: String, field: String): Long =
        text.lineSequence()
            .filter { it.startsWith(field) }
            .mapNotNull { it.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() }
            .sum() / 1024

    /**
     * Categories out of a native-memory-tracking summary. Lines look like
     * `-  Java Heap (reserved=6082560KB, committed=270336KB)`, and the committed
     * figure is the one that corresponds to memory the process actually took.
     */
    internal fun parseNativeMemorySummary(text: String): List<Region> =
        text.lineSequence()
            .mapNotNull(NMT_CATEGORY::find)
            .map { match ->
                val (name, committedKb) = match.destructured
                val mb = committedKb.toLong() / 1024
                Region(name.trim().lowercase(), usedMb = mb, committedMb = mb)
            }
            .filter { it.usedMb > 0 }
            .toList()

    private fun Long.toMb(): Long = this / (1024 * 1024)

    private val MAP_HEADER = Regex("^[0-9a-f]+-[0-9a-f]+ \\S+ \\S+ \\S+ \\S+\\s*(.*)$")
    private val NMT_CATEGORY = Regex("^-\\s+(.+?)\\s+\\(reserved=\\d+KB, committed=(\\d+)KB")
    private val GL_LIB = Regex("(iris|mesa|gallium|vulkan|libGL|libEGL|libgbm|drm)")

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
}
