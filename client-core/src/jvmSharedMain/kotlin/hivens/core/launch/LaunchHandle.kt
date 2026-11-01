package hivens.core.launch

import java.io.OutputStream

/**
 * Opaque handle to a spawned game process. Keeps `java.lang.Process` internal
 * to the launcher implementation so the core SPI -- and any alternate frontend
 * consuming [LaunchState] -- never depends on the JVM process type.
 */
interface LaunchHandle {
    /**
     * Suspends until the process exits and returns its exit code. The launcher
     * calls this on its IO launch coroutine; the implementation may block that
     * dispatcher (mirroring a direct `Process.waitFor()`), so cancelling the
     * launch job does not interrupt the wait -- the launcher sends [terminate]
     * first, which lets the wait return.
     */
    suspend fun awaitExit(): Int

    /** Request process termination (SIGTERM). Idempotent; safe to call after exit. */
    fun terminate()

    /** The process's stdin, for the in-game command-input sink. */
    val stdin: OutputStream
}
