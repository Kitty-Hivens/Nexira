package hivens.ui.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loudness survives a restart, and getting there costs one write.
 *
 * Both halves are the bug. The player opened at full volume every launch because
 * nothing stored the level; and the naive fix -- write on every change -- would
 * put a read-modify-write of the whole settings document on every pointer frame
 * of a drag, which is sixty file writes to move a slider across a card.
 *
 * No engine is touched here: every assertion is about the flow and the callback,
 * and Skinema's natives are not on the test classpath.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioVolumePersistenceTest {

    @Test
    fun `the stored level is what the player opens at`() = runTest {
        val player = AudioPlayer(scope = TestScope(testScheduler), initialVolume = 0.35f)
        assertEquals(0.35f, player.volume.value)
    }

    @Test
    fun `a level outside the range is brought into it rather than trusted`() = runTest {
        // The file is editable by hand and old files predate the field entirely.
        assertEquals(1f, AudioPlayer(TestScope(testScheduler), initialVolume = 4f).volume.value)
        assertEquals(0f, AudioPlayer(TestScope(testScheduler), initialVolume = -1f).volume.value)
    }

    /**
     * The settled write lands on [kotlinx.coroutines.Dispatchers.IO], which the test
     * scheduler does not drive: advancing the virtual clock releases the debounce but
     * the write itself is then a real thread's business. So the list is one a second
     * thread may append to, and the wait is for the value rather than for the clock.
     */
    private fun CopyOnWriteArrayList<Float>.awaitSize(n: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (size < n && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(n, size, "expected $n settled write(s), saw ${'$'}this")
    }

    @Test
    fun `a drag across the track is one write, of the value it ended on`() = runTest {
        val written = CopyOnWriteArrayList<Float>()
        val player = AudioPlayer(
            scope = TestScope(testScheduler),
            initialVolume = 1f,
            persistVolume = { written += it },
        )

        // Sixty frames of a drag, the way a pointer reports one.
        repeat(60) { i -> player.setVolume(i / 60f) }
        // Still inside the settle window: the UI already shows the new level and
        // the disk has not been told anything.
        advanceTimeBy(100)
        assertTrue(written.isEmpty(), "wrote mid-drag: $written")

        advanceUntilIdle()
        written.awaitSize(1)
        assertEquals(59 / 60f, written.single())
        assertEquals(59 / 60f, player.volume.value)
    }

    @Test
    fun `two settled changes are two writes`() = runTest {
        val written = CopyOnWriteArrayList<Float>()
        val player = AudioPlayer(
            scope = TestScope(testScheduler),
            initialVolume = 1f,
            persistVolume = { written += it },
        )
        player.setVolume(0.2f)
        advanceUntilIdle()
        written.awaitSize(1)
        player.setVolume(0.8f)
        advanceUntilIdle()
        written.awaitSize(2)
        assertEquals(listOf(0.2f, 0.8f), written.toList())
    }

    @Test
    fun `a failing write does not take the player down with it`() = runTest {
        // The settings file can be read-only, or on a full disk. Losing the stored
        // level is a nuisance; losing playback over it is not acceptable.
        val player = AudioPlayer(
            scope = TestScope(testScheduler),
            initialVolume = 1f,
            persistVolume = { error("disk full") },
        )
        player.setVolume(0.5f)
        advanceUntilIdle()
        assertEquals(0.5f, player.volume.value)
    }
}
