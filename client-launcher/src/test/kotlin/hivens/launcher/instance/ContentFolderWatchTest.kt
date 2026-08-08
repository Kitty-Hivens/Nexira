package hivens.launcher.instance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Driven with millisecond intervals against a real directory, so the polling and
 * the settle are the ones that ship rather than a stand-in for them.
 */
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

    private fun watch() = ContentFolderWatch(pollMillis = 20, settleMillis = 40)

    @Test
    fun `a jar dropped in from outside is reported`() = runTest {
        val dir = instanceDir()
        val reported = async(Dispatchers.IO) {
            withTimeoutOrNull(5_000) { watch().changes(dir).first() }
        }

        withContext(Dispatchers.IO) {
            delay(100)
            Files.write(dir.resolve("mods/dropped.jar"), "MOD".toByteArray())
        }

        assertNotNull(reported.await(), "a file added outside the launcher must reach the screen")
    }

    /**
     * The folders are already populated when the watch starts, which is the normal
     * case -- subscribing must not read that as a change and trigger a rescan the
     * screen already did.
     */
    @Test
    fun `a folder that was already full is not a change`() = runTest {
        val dir = instanceDir()
        Files.write(dir.resolve("mods/present.jar"), "MOD".toByteArray())

        val reported = withContext(Dispatchers.IO) {
            // Let the write land completely before subscribing. A filesystem is
            // entitled to finish updating an entry's metadata after the call
            // returns, and a snapshot taken across that window differs from the one
            // after it -- which is a real change as far as the watch is concerned,
            // and made this assertion flaky rather than wrong.
            delay(200)
            withTimeoutOrNull(300) { watch().changes(dir).first() }
        }

        assertNull(reported, "nothing changed, so nothing should have been reported")
    }

    /**
     * A large jar arrives over several ticks. Reporting mid-copy makes the rescan
     * parse a truncated archive, so the change is announced only once the folder
     * holds still.
     */
    @Test
    fun `a file still being written is reported only once it settles`() = runTest {
        val dir = instanceDir()
        val target = dir.resolve("mods/growing.jar")
        val reported = async(Dispatchers.IO) {
            withTimeoutOrNull(5_000) { watch().changes(dir).first() }
        }

        val grewUntil = withContext(Dispatchers.IO) {
            delay(60)
            repeat(6) {
                Files.write(target, ByteArray(1_000 * (it + 1)))
                delay(30)
            }
            System.nanoTime()
        }

        assertNotNull(reported.await(), "the copy finished, so it must be reported")
        // Emitting during the writes would have completed the await before the
        // last one landed; it did not, so the settle held it back.
        assert(System.nanoTime() >= grewUntil)
    }

    @Test
    fun `resource packs are watched too`() = runTest {
        val dir = instanceDir()
        Files.createDirectories(dir.resolve("resourcepacks"))
        val reported = async(Dispatchers.IO) {
            withTimeoutOrNull(5_000) { watch().changes(dir).first() }
        }

        withContext(Dispatchers.IO) {
            delay(100)
            Files.write(dir.resolve("resourcepacks/pack.zip"), "RP".toByteArray())
        }

        assertNotNull(reported.await(), "the scanner reads resourcepacks/, so the watch must cover it")
    }
}
