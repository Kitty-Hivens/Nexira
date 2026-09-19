package hivens.ui.audio

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the wallpaper session answers with no player behind it.
 *
 * A narrow test on purpose, and the reason is worth stating rather than leaving as
 * a gap somebody fills badly later. Everything this class does WITH a player runs
 * through skinema, and a skinema player needs the FFmpeg natives, which are not on
 * the test path -- the same wall [AudioPlayer] met, and it answered by extracting a
 * seam and testing the orchestration above it. There is no such seam here yet: the
 * background hands over a real player because it also needs frames out of it, and
 * frames are the one thing the music player's seam deliberately does not carry.
 *
 * So what is pinned here is the half that needs nothing: the detached state, and
 * the queue verbs doing nothing. The second is the decision most at risk of being
 * helpfully implemented by somebody who reads six empty overrides as unfinished
 * work rather than as the answer.
 */
class WallpaperSessionTest {

    private fun session(scope: TestScope, onVolume: (Float) -> Unit = {}) =
        WallpaperSession(scope, persistVolume = onVolume)

    @Test
    fun `with nothing attached it is idle and holds no queue`() = runTest {
        val s = session(this)
        assertEquals(PlaybackState.Idle, s.state.value)
        assertEquals(emptyList(), s.queue.value)
        assertEquals(-1, s.queueIndex.value)
        assertNull(s.track.value)
    }

    @Test
    fun `the queue verbs are inert, because a wallpaper is one clip`() = runTest {
        val s = session(this)
        val before = s.queue.value to s.queueIndex.value

        s.open(listOf(Path.of("/tmp/a.mp3"), Path.of("/tmp/b.mp3")))
        s.enqueue(listOf(Path.of("/tmp/c.mp3")))
        s.playAt(1)
        s.skipToNext()
        s.skipToPrevious()
        s.removeAt(0)

        assertEquals(
            before,
            s.queue.value to s.queueIndex.value,
            "a verb that moves between entries did something, and a wallpaper has no second entry to move to",
        )
    }

    @Test
    fun `a level set on the transport is clamped and handed on to be persisted`() = runTest {
        val written = mutableListOf<Float>()
        val s = session(this) { written += it }

        s.setVolume(0.4f)
        s.setVolume(1.7f)
        s.setVolume(-0.2f)

        assertEquals(listOf(0.4f, 1f, 0f), written, "the record has to receive what the flow reports")
        assertEquals(0f, s.volume.value)
    }

    @Test
    fun `a level the settings moved is reported without being written back`() = runTest {
        val written = mutableListOf<Float>()
        val s = session(this) { written += it }

        // The appearance panel's own slider already persists. Echoing it here would
        // write the same value a second time and, through the debounce above it,
        // for a drag that is still in flight.
        s.reportVolume(0.25f)

        assertEquals(0.25f, s.volume.value)
        assertEquals(emptyList(), written)
    }

    @Test
    fun `detaching leaves nothing loaded`() = runTest {
        val s = session(this)
        s.reportVolume(0.5f)
        s.detach()

        assertEquals(PlaybackState.Idle, s.state.value)
        assertEquals(emptyList(), s.queue.value)
        assertEquals(-1, s.queueIndex.value)
        // The level survives, because it is a setting rather than something the
        // loaded file brought with it.
        assertEquals(0.5f, s.volume.value)
    }
}
