package hivens.launcher.launch

import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.interfaces.IPackSyncService
import hivens.core.api.interfaces.RosterInspection
import hivens.core.api.interfaces.RosterVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watchdog is driven with millisecond settles so the tests exercise the real
 * polling against a real directory without waiting out the production window.
 */
class LaunchContentWatchdogTest {

    private val temps = mutableListOf<Path>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        temps.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        temps.add(dir)
        return dir
    }

    private fun instanceDir(): Path =
        tempDir("watchdog").also { Files.createDirectories(it.resolve("mods")) }

    /**
     * Answers a fixed script: each call returns the next inspection, repeating the
     * last one once the script runs out. Counts calls so a test can tell an
     * event-driven look from the settle pass.
     */
    private class ScriptedSync(private vararg val script: RosterInspection) : IPackSyncService {
        val calls = AtomicInteger(0)
        override fun relabel(clientDir: Path, mods: List<SmrtModEntry>, enabledState: Map<String, Boolean>): List<String> = emptyList()
        override suspend fun enforceRoster(clientDir: Path, expected: Map<String, String>?): RosterVerdict =
            RosterVerdict(verified = true)
        override suspend fun inspectRoster(clientDir: Path, expected: Map<String, String>?): RosterInspection {
            val i = calls.getAndIncrement()
            return script.getOrElse(i) { script.last() }
        }
    }

    @Test
    fun `a clean instance is reported clean after the settle`() = runTest {
        val dir = instanceDir()
        val sync = ScriptedSync(RosterInspection())

        val findings = LaunchContentWatchdog(sync, dir, expected = null, settleMillis = 150, pollMillis = 20).run()

        assertTrue(findings.isEmpty())
        assertTrue(sync.calls.get() >= 1, "the settle pass runs even when nothing ever happened")
    }

    @Test
    fun `the settle pass catches content the watch never reported`() = runTest {
        // The backstop on its own: no event is generated at all (the sync answers
        // dirty from the first look), which is the shape of a missed or unsupported
        // watch.
        val dir = instanceDir()
        val sync = ScriptedSync(RosterInspection(foreign = listOf("freecam.jar")))

        val findings = LaunchContentWatchdog(sync, dir, expected = null, settleMillis = 150, pollMillis = 20).run()

        assertEquals(listOf("freecam.jar"), findings)
    }

    /**
     * Answers from what is on disk at the moment it is asked, which is what a real
     * walk does. A finding therefore only comes back if the watchdog looked WHILE
     * the file was there -- the discriminator between reacting to an event and
     * waiting for the deadline.
     */
    private class DiskSensingSync(private val watched: Path) : IPackSyncService {
        override fun relabel(clientDir: Path, mods: List<SmrtModEntry>, enabledState: Map<String, Boolean>): List<String> = emptyList()
        override suspend fun enforceRoster(clientDir: Path, expected: Map<String, String>?): RosterVerdict =
            RosterVerdict(verified = true)
        override suspend fun inspectRoster(clientDir: Path, expected: Map<String, String>?): RosterInspection =
            if (Files.exists(watched)) RosterInspection(foreign = listOf(watched.fileName.toString()))
            else RosterInspection()
    }

    /**
     * The case the settle pass cannot see: a jar that is planted, picked up by the
     * loader, and unlinked again. Nothing is on disk by the time the deadline pass
     * walks the directory -- it is only ever seen while it is there.
     */
    @Test
    fun `a jar that appears and is removed again is still caught`() = runTest {
        val dir = instanceDir()
        val planted = dir.resolve("mods/freecam.jar")
        val sync = DiskSensingSync(planted)

        // Long enough that the deadline pass runs well after the file is gone: if
        // the change were not acted on, this returns clean.
        val watchdog = LaunchContentWatchdog(sync, dir, expected = null, settleMillis = 5_000, pollMillis = 20)
        val running = async(Dispatchers.IO) { watchdog.run() }
        withContext(Dispatchers.IO) {
            delay(100)
            Files.write(planted, "CHEAT".toByteArray())
            delay(300)
            Files.deleteIfExists(planted)
        }

        assertEquals(listOf("freecam.jar"), running.await(), "seen while it was there, or not at all")
    }

    @Test
    fun `an exiting game ends the watch instead of holding it to the deadline`() = runTest {
        val dir = instanceDir()
        val sync = ScriptedSync(RosterInspection())

        // Production settle: if cancellation were not observed, the join below
        // would sit here for a minute and a half.
        val watchdog = LaunchContentWatchdog(sync, dir, expected = null, pollMillis = 20)
        val running = async(Dispatchers.IO) { watchdog.run() }
        withContext(Dispatchers.IO) { delay(100) }
        running.cancel()

        // Real time, not runTest's virtual clock -- the point is that the blocking
        // poll actually lets go, which a scheduler that skips ahead cannot show.
        withContext(Dispatchers.IO) { withTimeout(5_000) { running.join() } }
        assertTrue(running.isCancelled)
    }

    @Test
    fun `an instance with no mods directory is not a finding`() = runTest {
        val dir = tempDir("watchdog-bare")
        val sync = ScriptedSync(RosterInspection(foreign = listOf("would-not-be-asked.jar")))

        val findings = LaunchContentWatchdog(sync, dir, expected = null, settleMillis = 100, pollMillis = 20).run()

        assertTrue(findings.isEmpty())
        assertEquals(0, sync.calls.get(), "nothing to hold to the pack, so nothing is claimed about it")
    }
}
