package hivens.launcher.instance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Driven on a virtual clock against a real directory.
 *
 * The waits are the mechanism here, so the tests advance them by hand rather than
 * sleeping through them: files are written synchronously, time moves only when the
 * test says so, and the outcome stops depending on how loaded the machine is. An
 * earlier version slept through the real intervals and answered differently under
 * load -- the same assertion passing or failing with the code unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentFolderWatchTest {

    private val temps = mutableListOf<Path>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        temps.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun instanceDir(): Path =
        Files.createTempDirectory("content-watch").also {
            temps.add(it)
            Files.createDirectories(it.resolve("mods"))
        }

    private val poll = 20L
    private val settle = 40L

    @Test
    fun `a jar dropped in from outside is reported`() = runTest {
        val dir = instanceDir()
        var seen = 0
        ContentFolderWatch(poll, settle, StandardTestDispatcher(testScheduler))
            .changes(dir)
            .onEach { seen++ }
            .launchIn(backgroundScope)
        advanceTimeBy(poll * 2)

        Files.write(dir.resolve("mods/dropped.jar"), "MOD".toByteArray())
        advanceTimeBy(poll + settle * 2)

        assertEquals(1, seen, "a file added outside the launcher must reach the screen")
    }

    /**
     * The folders are already populated when the watch starts, which is the normal
     * case -- subscribing must not read that as a change and trigger a rescan the
     * screen has already done.
     */
    @Test
    fun `a folder that was already full is not a change`() = runTest {
        val dir = instanceDir()
        Files.write(dir.resolve("mods/present.jar"), "MOD".toByteArray())

        var seen = 0
        ContentFolderWatch(poll, settle, StandardTestDispatcher(testScheduler))
            .changes(dir)
            .onEach { seen++ }
            .launchIn(backgroundScope)
        advanceTimeBy(poll * 10)

        assertEquals(0, seen, "nothing changed, so nothing should have been reported")
    }

    /**
     * A large jar arrives over several ticks. Reporting mid-copy makes the rescan
     * parse a truncated archive, so the change is announced only once the folder
     * holds still -- which also collapses a batch of arrivals into one rescan
     * instead of one per file.
     */
    @Test
    fun `a file still being written is reported once, after it settles`() = runTest {
        val dir = instanceDir()
        val target = dir.resolve("mods/growing.jar")
        var seen = 0
        ContentFolderWatch(poll, settle, StandardTestDispatcher(testScheduler))
            .changes(dir)
            .onEach { seen++ }
            .launchIn(backgroundScope)
        advanceTimeBy(poll * 2)

        repeat(6) { round ->
            Files.write(target, ByteArray(1_000 * (round + 1)))
            advanceTimeBy(poll)
            assertEquals(0, seen, "reported while the file was still growing")
        }

        // Explicit rather than advanceUntilIdle(): the flow never goes idle, it
        // polls forever, so "until idle" has nothing to settle on here.
        advanceTimeBy(settle * 3)
        assertEquals(1, seen, "the copy finished, so it must be reported -- once")
    }

    @Test
    fun `resource packs are watched too`() = runTest {
        val dir = instanceDir()
        Files.createDirectories(dir.resolve("resourcepacks"))
        var seen = 0
        ContentFolderWatch(poll, settle, StandardTestDispatcher(testScheduler))
            .changes(dir)
            .onEach { seen++ }
            .launchIn(backgroundScope)
        advanceTimeBy(poll * 2)

        Files.write(dir.resolve("resourcepacks/pack.zip"), "RP".toByteArray())
        advanceTimeBy(poll + settle * 2)

        assertEquals(1, seen, "the scanner reads resourcepacks/, so the watch must cover it")
    }
}
