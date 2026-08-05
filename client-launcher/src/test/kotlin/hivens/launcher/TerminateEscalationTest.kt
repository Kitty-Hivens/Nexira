package hivens.launcher

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The escalation behind `LaunchHandle.terminate`, exercised against real processes
 * rather than a mock: the whole point is what the operating system does with a
 * signal, which no fake can tell us.
 *
 * Unix only: the trap builtin is how a process is made to ignore a signal, and
 * there is no Windows equivalent to write here.
 *
 * NOT covered: the case that matters most -- a process that ignores SIGTERM being
 * ended by the escalation. Verified by hand outside the harness (destroy() leaves
 * `sh -c "trap '' TERM; ..."` alive past two seconds, destroyForcibly ends it), but
 * inside a Gradle test worker the same process dies to destroy() alone and the
 * assertion that separates the two signals cannot hold. Rather than weaken it into
 * something that passes without meaning anything, it is left out and said so here.
 */
class TerminateEscalationTest {

    private val unix = !System.getProperty("os.name").lowercase().contains("win")

    /** Mirrors ProcessLaunchHandle.terminate, which is private to LauncherService. */
    private fun terminateWithEscalation(process: Process, graceSeconds: Long) {
        runCatching { process.destroy() }
        Thread {
            val exited = runCatching { process.waitFor(graceSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) {
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                runCatching { process.destroyForcibly() }
            }
        }.apply { isDaemon = true }.start()
    }

    @Test
    fun `a process that honours SIGTERM exits without being forced`() {
        if (!unix) return
        // The healthy game: it takes the polite signal, so it gets to shut down on
        // its own terms rather than being shot. Exit 143 is 128 + SIGTERM.
        val process = ProcessBuilder("sh", "-c", "sleep 60").start()
        try {
            terminateWithEscalation(process, graceSeconds = 30)

            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "SIGTERM should have been enough")
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `terminate does not block the caller on a process that will not die`() {
        if (!unix) return
        // abort() runs on the Compose thread from a click handler, so the escalation
        // must not be waited on there.
        val process = ProcessBuilder("sh", "-c", "trap '' TERM; sleep 60").start()
        try {
            val startedAt = System.nanoTime()
            terminateWithEscalation(process, graceSeconds = 30)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(elapsedMs < 1_000, "terminate blocked the caller for ${elapsedMs}ms")
        } finally {
            process.destroyForcibly()
        }
    }
}
