package hivens.ui.audio

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What wakes the media session, and how often.
 *
 * The bridge used to read the player on a timer, which was a delay added to every
 * change and, by accident, the only thing rate-limiting the one input a hand moves
 * directly. Driving it from the player's own flows fixed the first and removed the
 * second, so the second is pinned here: a slider reports on every pointer frame,
 * the player passes each one through, and a property-change signal per frame is a
 * storm on a bus every media widget on the desktop is listening to.
 */
class MediaSessionStreamTest {

    private val artDir: Path = Path.of("/tmp/nexira-test-covers-unused")

    @Test
    fun `a volume drag does not become one snapshot per pointer frame`() = runTest {
        val player = AudioPlayer(scope = backgroundScope)
        val bridge = MediaSessionBridge(player, backgroundScope, artDir)

        val seen = mutableListOf<Float>()
        val collector = backgroundScope.launch { bridge.snapshots().collect { seen += it.volume } }
        runCurrent()

        // Sixty frames of a drag, which is what half a second of dragging a slider
        // across its track actually produces.
        repeat(60) { frame -> player.setVolume(frame / 59f) }
        runCurrent()
        advanceTimeBy(VOLUME_SETTLE_MS)
        runCurrent()
        collector.cancel()

        assertTrue(
            seen.size <= MOST_SNAPSHOTS,
            "a drag of sixty frames must not wake the session sixty times: ${seen.size} snapshots",
        )
        assertEquals(1f, seen.last(), "and where the hand stopped has to arrive")
    }

    @Test
    fun `a paused player still reports a volume somebody moved`() = runTest {
        // The reason the loudness is combined in at all rather than read off the
        // state: a paused player emits no state, so a slider moved while paused
        // would otherwise reach the desktop only on the next play.
        val player = AudioPlayer(scope = backgroundScope)
        val bridge = MediaSessionBridge(player, backgroundScope, artDir)

        val seen = mutableListOf<Float>()
        val collector = backgroundScope.launch { bridge.snapshots().collect { seen += it.volume } }
        advanceTimeBy(VOLUME_SETTLE_MS)
        runCurrent()
        val before = seen.size

        player.setVolume(0.25f)
        advanceTimeBy(VOLUME_SETTLE_MS)
        runCurrent()
        collector.cancel()

        assertTrue(seen.size > before, "the move has to wake the stream")
        assertEquals(0.25f, seen.last())
    }

    private companion object {
        /** Comfortably past one sampling window, so the settled value has landed. */
        const val VOLUME_SETTLE_MS = 400L

        /**
         * One for the window the drag ran in and one for where it stopped, with a
         * little room for the scheduler. The number being guarded is sixty.
         */
        const val MOST_SNAPSHOTS = 6
    }
}
