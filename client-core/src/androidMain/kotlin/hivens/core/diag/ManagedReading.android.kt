package hivens.core.diag

import android.os.Process
import android.os.SystemClock
import hivens.core.diag.MemoryReport.Region

// ART has no management beans: the heap comes from Runtime, and what only a HotSpot
// build can tell (pools, collectors, ergonomics, native tracking) is left out.
internal actual fun readManaged(withNativeCategories: Boolean): ManagedReading {
    val runtime = Runtime.getRuntime()
    val committed = runtime.totalMemory()
    return ManagedReading(
        heap = Region("heap", (committed - runtime.freeMemory()).toMb(), committed.toMb(), runtime.maxMemory().toMb()),
        pools = emptyList(),
        collectors = emptyList(),
        threads = Thread.getAllStackTraces().size,
        classesLoaded = null,
        directBufferMb = 0,
        uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
        ergonomics = emptyList(),
        nativeMemory = null,
    )
}

internal actual fun requestGarbageCollection() = Runtime.getRuntime().gc()

internal actual fun totalGcPauseMs(): Long = 0L
