package hivens.ui.audio

import androidx.compose.ui.graphics.ImageBitmap
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.LoopMode
import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.PlaybackState as SessionPlayback
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import hivens.ui.testImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the desktop is told, driven as a run rather than asserted a field at a
 * time.
 *
 * Five things this published were stale or wrong at once, and every one was found
 * by reading the code rather than by using the launcher. That is the argument for
 * this file: each of them is a property of a sequence, none of them throws, and
 * a widget showing last minute's position looks exactly like a widget showing
 * this minute's until somebody watches it for a while.
 *
 * The session is a recording stand-in rather than a bus. What is under test is
 * what the player says, not what libdbus does with it.
 */
class MediaSessionPublishTest {

    // -- the stand-in ----------------------------------------------------------

    private class RecordingSession : MediaSession {
        val published = mutableListOf<SessionState>()
        val seeks = mutableListOf<Long>()

        override val capabilities: Capabilities = Capabilities.NONE
        override val isOpen: Boolean = true

        override fun publish(state: SessionState) {
            published += state
        }

        override fun seeked(positionMicros: Long) {
            seeks += positionMicros
        }

        override fun onCommand(handler: (SessionCommand) -> Unit): () -> Unit = {}

        override fun close() = Unit
    }

    // -- the run ---------------------------------------------------------------

    private val first: Path = Path.of("/music/01 - first.flac")
    private val second: Path = Path.of("/music/02 - second.flac")

    private fun playing(file: Path, positionMs: Long, durationMs: Long = 200_000L) =
        PlaybackState.Playing(file, positionMs, durationMs)

    private fun snapshot(
        state: PlaybackState,
        track: TrackInfo? = null,
        volume: Float = 1f,
        queueSize: Int = 1,
        queueIndex: Int = 0,
        repeat: RepeatMode = RepeatMode.Off,
    ) = PlayerSnapshot(state, track, volume, queueSize, queueIndex, repeat)

    @Test
    fun `the position is published on every poll, not only when something else moved`() {
        // The defect this pins reads backwards, which is why it survived: MPRIS
        // leaves Position out of its change notifications on purpose and a reader
        // polls the property instead, so a publish withheld because "only the
        // position moved" is the scrubber standing still in every widget on the
        // bus. Nothing else changes across this run.
        val session = RecordingSession()
        val publisher = SessionPublisher(session)
        val positions = listOf(0L, 500L, 1_000L, 1_500L, 2_000L)
        for (at in positions) publisher.publish(snapshot(playing(first, at)), artUrl = null)

        assertEquals(positions.size, session.published.size, "every poll must reach the session")
        assertEquals(
            positions.map { it * 1_000L },
            session.published.map { it.positionMicros },
            "the position each poll carried",
        )
    }

    @Test
    fun `ordinary playing announces no seek`() {
        val session = RecordingSession()
        val publisher = SessionPublisher(session)
        for (at in 0L..5_000L step SESSION_TICK_MS) publisher.publish(snapshot(playing(first, at)), artUrl = null)
        assertTrue(session.seeks.isEmpty(), "advancing by one poll at a time is not a jump: ${session.seeks}")
    }

    @Test
    fun `a jump backwards and a jump forwards are both announced`() {
        val session = RecordingSession()
        val publisher = SessionPublisher(session)
        publisher.publish(snapshot(playing(first, 60_000L)), artUrl = null)
        publisher.publish(snapshot(playing(first, 10_000L)), artUrl = null)
        publisher.publish(snapshot(playing(first, 120_000L)), artUrl = null)
        assertEquals(listOf(10_000_000L, 120_000_000L), session.seeks)
    }

    @Test
    fun `a track change announces the position it landed on`() {
        // A reader extrapolating from the last anchor it was given has no way to
        // see a track change as anything but the old track still running, so the
        // drop to zero has to be said out loud.
        val session = RecordingSession()
        val publisher = SessionPublisher(session)
        publisher.publish(snapshot(playing(first, 180_000L)), artUrl = null)
        publisher.publish(snapshot(playing(second, 0L), queueIndex = 1, queueSize = 2), artUrl = null)
        assertEquals(listOf(0L), session.seeks)
    }

    @Test
    fun `a late poll is not a seek`() {
        // The margin exists for a poll that ran long. Without it every stutter in
        // the launcher would reach the desktop as a scrub nobody performed.
        val session = RecordingSession()
        val publisher = SessionPublisher(session)
        publisher.publish(snapshot(playing(first, 0L)), artUrl = null)
        publisher.publish(snapshot(playing(first, SESSION_TICK_MS + SESSION_JUMP_MARGIN_MS)), artUrl = null)
        assertTrue(session.seeks.isEmpty(), "a poll one margin late is still playing: ${session.seeks}")
    }

    // -- the state itself ------------------------------------------------------

    @Test
    fun `the player's own volume is carried, not a constant`() {
        // Published nowhere, this sat at the type's default of full: a widget's
        // slider stood at the top whatever the player was set to, and dragging it
        // snapped straight back on the next read.
        for (level in listOf(0f, 0.25f, 0.5f, 1f)) {
            val state = sessionStateOf(snapshot(playing(first, 0L), volume = level), artUrl = null)
            assertEquals(level.toDouble(), state.volume, "volume $level")
        }
    }

    @Test
    fun `pause is offered for a loaded track whatever the transport is doing`() {
        // The property asks whether this player can be paused at all. Tied to the
        // transport it flipped on every press, which is a capability appearing and
        // disappearing under a widget rather than a button changing its face.
        val loaded = listOf(
            playing(first, 1_000L),
            PlaybackState.Paused(first, 1_000L, 200_000L),
            PlaybackState.Ready(first, 0L, 200_000L),
        )
        for (state in loaded) {
            assertTrue(sessionStateOf(snapshot(state), artUrl = null).canPause, "in $state")
        }
        assertTrue(!sessionStateOf(snapshot(PlaybackState.Idle), artUrl = null).canPause, "nothing loaded")
    }

    @Test
    fun `a track id names the entry and not only the file`() {
        // A queue can hold the same file twice, and a seek is checked against this
        // before it is acted on, so two entries of one file must not share an id.
        val here = trackIdOf(first, queueIndex = 0)
        val again = trackIdOf(first, queueIndex = 3)
        assertNotEquals(here, again, "the same file at two places in the queue")
        assertNull(trackIdOf(null, queueIndex = 0), "nothing loaded names no track")
    }

    @Test
    fun `a seek naming another track is refused, and one naming none is not`() {
        val current = trackIdOf(second, queueIndex = 1)
        assertTrue(acceptsSeek(current, current), "the track the sender named is the one playing")
        assertTrue(!acceptsSeek(trackIdOf(first, 0), current), "the sender was a poll behind")
        // A reader that carries no identity is making no claim about which track
        // this is. Refusing it would drop every seek from such a widget.
        assertTrue(acceptsSeek(null, current), "no claim is not a wrong claim")
    }

    @Test
    fun `nothing loaded publishes empty metadata and a stopped transport`() {
        val state = sessionStateOf(snapshot(PlaybackState.Idle), artUrl = null)
        assertEquals(SessionPlayback.STOPPED, state.playback)
        assertNull(state.metadata.title)
        assertNull(state.metadata.trackId)
        assertTrue(!state.canPlay)
    }

    @Test
    fun `a loaded but unplayed track reads as paused rather than stopped`() {
        // Ready is the launcher's own distinction and the protocol has no third
        // state for it. Stopped would tell a widget to forget the position it is
        // holding, which is the one thing Ready means to keep.
        val state = sessionStateOf(snapshot(PlaybackState.Ready(first, 0L, 200_000L)), artUrl = null)
        assertEquals(SessionPlayback.PAUSED, state.playback)
        assertTrue(state.canPlay)
    }

    @Test
    fun `seeking is offered only where a duration is known`() {
        val unknown = sessionStateOf(snapshot(playing(first, 0L, durationMs = 0L)), artUrl = null)
        val known = sessionStateOf(snapshot(playing(first, 0L, durationMs = 200_000L)), artUrl = null)
        assertTrue(!unknown.canSeek, "a container that has not said how long it is")
        assertTrue(known.canSeek)
    }

    @Test
    fun `skipping is offered only where there is somewhere to skip to`() {
        assertTrue(!sessionStateOf(snapshot(playing(first, 0L), queueSize = 1), artUrl = null).canGoNext)
        val two = sessionStateOf(snapshot(playing(first, 0L), queueSize = 2), artUrl = null)
        assertTrue(two.canGoNext && two.canGoPrevious)
    }

    @Test
    fun `every repeat mode maps onto the protocol's own three`() {
        val expected = mapOf(
            RepeatMode.Off to LoopMode.NONE,
            RepeatMode.One to LoopMode.TRACK,
            RepeatMode.Queue to LoopMode.PLAYLIST,
        )
        for ((mode, loop) in expected) {
            assertEquals(loop, sessionStateOf(snapshot(playing(first, 0L), repeat = mode), artUrl = null).loop)
        }
    }

    // -- the cover -------------------------------------------------------------

    @Test
    fun `a new track never shows the previous track's cover`() = runTest {
        // The player clears its metadata on load and fills it again from the decode
        // thread a moment later. Through that gap the new title used to stand
        // beside the old picture, because the cover was keyed on the track and
        // there was no track to key on.
        val trail = CoverTrail()
        var writes = 0
        suspend fun write(artwork: ImageBitmap?): String? {
            writes += 1
            return artwork?.let { "file:///covers/$writes.png" }
        }

        val firstTrack = TrackInfo(title = "First", artwork = testImageBitmap())
        val secondTrack = TrackInfo(title = "Second", artwork = testImageBitmap())

        assertNull(trail.urlFor(snapshot(playing(first, 0L), track = null), ::write), "tags have not landed")
        val settled = trail.urlFor(snapshot(playing(first, 500L), track = firstTrack), ::write)
        assertEquals("file:///covers/1.png", settled)

        // The load, where the player has the new file and no metadata for it yet.
        assertNull(
            trail.urlFor(snapshot(playing(second, 0L), track = null, queueIndex = 1), ::write),
            "the previous cover must go with the previous file",
        )
        val next = trail.urlFor(snapshot(playing(second, 500L), track = secondTrack, queueIndex = 1), ::write)
        assertEquals("file:///covers/2.png", next, "and the new one arrives with the tags")
    }

    @Test
    fun `a cover is written once per track, not once per poll`() = runTest {
        val trail = CoverTrail()
        var writes = 0
        suspend fun write(artwork: ImageBitmap?): String? {
            writes += 1
            return artwork?.let { "file:///covers/$writes.png" }
        }

        val track = TrackInfo(title = "First", artwork = testImageBitmap())
        repeat(20) { poll ->
            trail.urlFor(snapshot(playing(first, poll * SESSION_TICK_MS), track = track), ::write)
        }
        assertEquals(1, writes, "a cover re-encoded every poll is a megapixel of work twice a second")
    }

    @Test
    fun `a track with no picture is not asked twice`() = runTest {
        val trail = CoverTrail()
        var writes = 0
        suspend fun write(artwork: ImageBitmap?): String? {
            writes += 1
            return artwork?.let { "file:///covers/$writes.png" }
        }

        val bare = TrackInfo(title = "No cover", artwork = null)
        repeat(5) { trail.urlFor(snapshot(playing(first, 0L), track = bare), ::write) }
        assertEquals(1, writes, "the answer for a file with no picture does not change")
        assertNull(trail.urlFor(snapshot(playing(first, 0L), track = bare), ::write))
    }

    @Test
    fun `an emptied queue drops the cover with the file`() = runTest {
        val trail = CoverTrail()
        val track = TrackInfo(title = "First", artwork = testImageBitmap())
        suspend fun write(artwork: ImageBitmap?): String? = artwork?.let { "file:///covers/one.png" }

        assertEquals("file:///covers/one.png", trail.urlFor(snapshot(playing(first, 0L), track = track), ::write))
        assertNull(
            trail.urlFor(snapshot(PlaybackState.Idle, track = null), ::write),
            "a player with nothing loaded has no cover to show",
        )
    }
}
