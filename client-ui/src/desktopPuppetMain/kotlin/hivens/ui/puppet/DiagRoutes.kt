package hivens.ui.puppet

import hivens.core.diag.ActionRing
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.lang.management.LockInfo
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.time.Instant

/**
 * Read-only `/diag/...` endpoints that expose JVM internals + Nexira's own
 * action ring as JSON, intended for AI / automated profiling rather than
 * human eyes. Pair with the existing UI-driving endpoints to drive Nexira
 * through a deterministic reproduction (login -> click play) and then poll
 * `/diag/snapshot` at the freeze point to capture exactly which thread
 * holds what.
 *
 * **Off-Swing on purpose.** Ktor handlers run on the engine's IO
 * dispatcher; this file deliberately does NOT hop to `Dispatchers.Swing`
 * for the data collection. Reading from `Dispatchers.Swing` would mean the
 * thread we are trying to diagnose (AWT-EventQueue / Compose Recomposer)
 * is the one serving the request -- a heisenbug where the diagnostic call
 * itself unblocks what it was supposed to measure. JMX beans + the
 * concurrent ring buffer are all safe off-thread.
 *
 * **Endpoints (all GET, no payload):**
 *   * `/diag/threads`  -> ThreadMXBean dump with locks + deadlock list
 *   * `/diag/jvm`      -> memory, GC, runtime, OS load
 *   * `/diag/actions`  -> [ActionRing] last 64 entries oldest-first
 *   * `/diag/snapshot` -> all of the above plus the UI snapshot from
 *                        `PuppetRegistry`, in one round-trip.
 *
 * **Response shape favors machine parsing over compactness.** Lock and
 * stack frame info is explicit-fielded rather than stringified so a
 * consumer can pivot by class/method/file without regex. Sizes for a
 * typical Nexira process: threads ~200 KB, snapshot ~250 KB. Both fine on
 * localhost.
 */
private object DiagCollector {

    fun threads(): DiagThreadsResponse {
        val bean = ManagementFactory.getThreadMXBean()
        // (lockedMonitors=true, lockedSynchronizers=true) -- both are
        // required to reconstruct who-holds-what during a suspected
        // deadlock or contention freeze. Cost is one safepoint stop;
        // acceptable for dev tooling.
        val infos: Array<ThreadInfo> = bean.dumpAllThreads(true, true)
        val deadlocks = bean.findDeadlockedThreads()?.toList() ?: emptyList()
        return DiagThreadsResponse(
            timestamp    = Instant.now().toString(),
            threadCount  = infos.size,
            deadlocked   = deadlocks,
            threads      = infos.map { it.toDiagThread() },
        )
    }

    fun jvm(): DiagJvmResponse {
        val rt      = ManagementFactory.getRuntimeMXBean()
        val mem     = ManagementFactory.getMemoryMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        val os      = ManagementFactory.getOperatingSystemMXBean()
        val heap    = mem.heapMemoryUsage
        val nonHeap = mem.nonHeapMemoryUsage
        return DiagJvmResponse(
            timestamp               = Instant.now().toString(),
            uptimeMs                = rt.uptime,
            pid                     = ProcessHandle.current().pid(),
            javaVersion             = System.getProperty("java.version", ""),
            javaVendor              = System.getProperty("java.vendor", ""),
            jvmName                 = rt.vmName,
            jvmArgs                 = rt.inputArguments,
            osName                  = os.name,
            osArch                  = os.arch,
            osVersion               = os.version,
            availableProcessors     = os.availableProcessors,
            // -1.0 on Windows by JDK design; not an error, just unsupported there.
            systemLoadAverage       = os.systemLoadAverage,
            threadCount             = threads.threadCount,
            peakThreadCount         = threads.peakThreadCount,
            daemonThreadCount       = threads.daemonThreadCount,
            totalStartedThreadCount = threads.totalStartedThreadCount,
            heap                    = DiagMemoryPool("heap",    heap.used,    heap.committed,    heap.max),
            nonHeap                 = DiagMemoryPool("nonHeap", nonHeap.used, nonHeap.committed, nonHeap.max),
            gc                      = ManagementFactory.getGarbageCollectorMXBeans().map {
                DiagGcStats(it.name, it.collectionCount, it.collectionTime)
            },
        )
    }

    fun actions(): DiagActionsResponse = DiagActionsResponse(
        timestamp = Instant.now().toString(),
        capacity  = ActionRing.CAPACITY,
        entries   = ActionRing.snapshot().map {
            DiagActionEntry(timestamp = it.timestamp.toString(), text = it.text)
        },
    )

    private fun ThreadInfo.toDiagThread(): DiagThread = DiagThread(
        id                  = threadId,
        name                = threadName,
        state               = threadState.name,
        suspended           = isSuspended,
        inNative            = isInNative,
        blockedOn           = lockInfo?.toDiag(),
        blockedOnOwnerName  = lockOwnerName,
        blockedOnOwnerId    = lockOwnerId.takeIf { it >= 0 },
        blockedTimeMs       = blockedTime.takeIf { it >= 0 },
        blockedCount        = blockedCount,
        waitedTimeMs        = waitedTime.takeIf { it >= 0 },
        waitedCount         = waitedCount,
        stack               = stackTrace.map {
            DiagFrame(
                classMethod = "${it.className}.${it.methodName}",
                file        = it.fileName,
                line        = it.lineNumber,
            )
        },
        lockedMonitors      = lockedMonitors.map { it.toDiag() },
        lockedSynchronizers = lockedSynchronizers.map { it.toDiag() },
    )

    private fun LockInfo.toDiag(): DiagLock =
        DiagLock(className = className, identityHashCode = identityHashCode)
}

// ── Response shapes ──────────────────────────────────────────────────────
//
// All field names are stable and considered API for puppet consumers.
// Add fields freely; rename only with a corresponding bump to consumer
// scripts in `dev-tools/puppet/`.

@Serializable
internal data class DiagFrame(
    val classMethod: String,
    val file: String? = null,
    val line: Int = -1,
)

@Serializable
internal data class DiagLock(
    val className: String,
    val identityHashCode: Int,
)

@Serializable
internal data class DiagThread(
    val id: Long,
    val name: String,
    /** One of [Thread.State] names: NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED. */
    val state: String,
    val suspended: Boolean,
    /** True when the thread is currently executing native (JNI/JNA) code. */
    val inNative: Boolean,
    /** Lock the thread is blocked on (state=BLOCKED) or waiting on (state=WAITING/TIMED_WAITING), null otherwise. */
    val blockedOn: DiagLock? = null,
    val blockedOnOwnerName: String? = null,
    val blockedOnOwnerId: Long? = null,
    /** -1 if thread contention monitoring disabled. */
    val blockedTimeMs: Long? = null,
    val blockedCount: Long,
    val waitedTimeMs: Long? = null,
    val waitedCount: Long,
    val stack: List<DiagFrame>,
    /** Object monitors held (synchronized blocks the thread has entered). */
    val lockedMonitors: List<DiagLock> = emptyList(),
    /** `java.util.concurrent` AbstractOwnableSynchronizer locks held (ReentrantLock etc). */
    val lockedSynchronizers: List<DiagLock> = emptyList(),
)

@Serializable
internal data class DiagThreadsResponse(
    val timestamp: String,
    val threadCount: Int,
    /** Thread IDs of the deadlock cycle found by ThreadMXBean.findDeadlockedThreads(), or empty. */
    val deadlocked: List<Long>,
    val threads: List<DiagThread>,
)

@Serializable
internal data class DiagMemoryPool(
    val name: String,
    val usedBytes: Long,
    val committedBytes: Long,
    /** -1 when no upper bound is defined. */
    val maxBytes: Long,
)

@Serializable
internal data class DiagGcStats(
    val name: String,
    val collectionCount: Long,
    val collectionTimeMs: Long,
)

@Serializable
internal data class DiagJvmResponse(
    val timestamp: String,
    val uptimeMs: Long,
    val pid: Long,
    val javaVersion: String,
    val javaVendor: String,
    val jvmName: String,
    val jvmArgs: List<String>,
    val osName: String,
    val osArch: String,
    val osVersion: String,
    val availableProcessors: Int,
    /** Unix load avg over the last minute; -1.0 on Windows (unsupported). */
    val systemLoadAverage: Double,
    val threadCount: Int,
    val peakThreadCount: Int,
    val daemonThreadCount: Int,
    val totalStartedThreadCount: Long,
    val heap: DiagMemoryPool,
    val nonHeap: DiagMemoryPool,
    val gc: List<DiagGcStats>,
)

@Serializable
internal data class DiagActionEntry(
    /** ISO-8601 instant. */
    val timestamp: String,
    val text: String,
)

@Serializable
internal data class DiagActionsResponse(
    val timestamp: String,
    val capacity: Int,
    /** Oldest first. */
    val entries: List<DiagActionEntry>,
)

@Serializable
internal data class DiagSnapshot(
    val timestamp: String,
    val threads: DiagThreadsResponse,
    val jvm: DiagJvmResponse,
    val actions: DiagActionsResponse,
    val ui: PuppetSnapshot,
)

/**
 * Installs the `/diag/...` routes. Called from [RealPuppetServer]'s routing
 * block alongside the existing UI-driving endpoints. Kept as an extension
 * on [Routing] so the puppet server file stays a one-liner per endpoint
 * family.
 */
internal fun Routing.diagRoutes() {
    get("/diag/threads")  { call.respond(DiagCollector.threads()) }
    get("/diag/jvm")      { call.respond(DiagCollector.jvm()) }
    get("/diag/actions")  { call.respond(DiagCollector.actions()) }
    get("/diag/snapshot") {
        call.respond(
            DiagSnapshot(
                timestamp = Instant.now().toString(),
                threads   = DiagCollector.threads(),
                jvm       = DiagCollector.jvm(),
                actions   = DiagCollector.actions(),
                ui        = PuppetRegistry.snapshot(),
            )
        )
    }
}
