package hivens.core.diag

/**
 * What the managed runtime says about itself, in the units [MemoryReport] shows.
 *
 * The management beans that fill it exist on a desktop JVM and nowhere else, so each
 * host reads its own and the report built on top of it stays the same.
 */
internal class ManagedReading(
    val heap: MemoryReport.Region,
    val pools: List<MemoryReport.Region>,
    val collectors: List<MemoryReport.Collector>,
    val threads: Int,
    val classesLoaded: Long?,
    val directBufferMb: Long,
    val uptimeMs: Long,
    val ergonomics: List<Pair<String, String>>,
    /** The raw native-memory-tracking summary, when it was asked for and the runtime has one. */
    val nativeMemory: String?,
)

internal expect fun readManaged(withNativeCategories: Boolean): ManagedReading

/** Asks the collector to run now. */
internal expect fun requestGarbageCollection()

/** Time the application has spent stopped by collections since the process began. */
internal expect fun totalGcPauseMs(): Long

internal fun Long.toMb(): Long = this / (1024 * 1024)
