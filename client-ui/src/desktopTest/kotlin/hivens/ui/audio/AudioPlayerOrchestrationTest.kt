package hivens.ui.audio

import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.player.VideoPlayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the player does around an open file, rather than to one.
 *
 * Every decision the launcher makes about playback lives here and none of it could
 * be reached before: the queue's behaviour at its end, what a repeat mode means,
 * what a scrub into a finished track reopens into, whether a track running into
 * the next one is ever called paused, and what happens to a stream when the engine
 * refuses to open. The pure helpers beside this file were extracted for the same
 * reason and caught real defects, but they answer only the questions with no
 * engine in them.
 *
 * The engine is a stand-in that holds a state and counts calls. It is not a
 * simulation of skinema: what is under test is the orchestration, and the one
 * property borrowed from the real thing is the one the orchestration turns on,
 * that closing is what releases a file.
 */
class AudioPlayerOrchestrationTest {

    // -- the stand-in ----------------------------------------------------------

    private class FakeEngine(val file: Path) : PlaybackEngine {
        override var state: VideoPlayer.State = VideoPlayer.State.Playing
        override var durationNanos: Long? = 200_000L * 1_000_000L
        override val tags: Map<String, String> = emptyMap()
        override val coverArt: ByteArray? = null

        var positionNanos = 0L
        var lastVolume = -1f
        var paused = false
        var resumed = false
        var closed = false
        var seeks = mutableListOf<Long>()

        override fun positionNanos(): Long = positionNanos
        override fun seek(nanos: Long) {
            seeks += nanos
            positionNanos = nanos
        }
        override fun resume() { resumed = true; paused = false }
        override fun pause() { paused = true }
        override fun setVolume(level: Float) { lastVolume = level }
        override fun close() { closed = true }
    }

    /** Hands out fakes and remembers every one, so a released engine can be checked. */
    private class FakeEngines : PlaybackEngines {
        val opened = mutableListOf<FakeEngine>()
        val sinks = mutableListOf<PcmSink?>()
        var refuse: (() -> Throwable)? = null

        override fun open(file: Path, sink: PcmSink?): PlaybackEngine {
            sinks += sink
            refuse?.let { throw it() }
            return FakeEngine(file).also { opened += it }
        }
    }

    // -- the files -------------------------------------------------------------

    /**
     * Real paths on disk, because the player drops a restored entry whose file has
     * gone and would drop these too.
     */
    private fun tracks(count: Int): List<Path> {
        val dir = Files.createTempDirectory("nexira-orchestration")
        dir.toFile().deleteOnExit()
        return (1..count).map { n ->
            Files.createFile(dir.resolve("%02d - track.flac".format(n))).also { it.toFile().deleteOnExit() }
        }
    }

    // -- opening ---------------------------------------------------------------

    @Test
    fun `opening replaces the queue and loads its first entry without sounding`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(3)

        player.open(files)
        runCurrent()

        assertEquals(files, player.queue.value)
        assertEquals(0, player.queueIndex.value)
        // Silenced AND paused. Skinema begins playing as soon as it is built, so
        // an open nobody asked to hear has to do both.
        val engine = engines.opened.single()
        assertEquals(0f, engine.lastVolume, "an open the user did not ask to hear")
        assertTrue(engine.paused, "and it has to be stopped, not only quietened")
    }

    @Test
    fun `appending leaves what is playing alone`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(4)

        player.open(files.take(2))
        runCurrent()
        val first = engines.opened.single()

        player.enqueue(files.drop(2))
        runCurrent()

        assertEquals(files, player.queue.value, "the appended entries are at the end")
        assertEquals(0, player.queueIndex.value, "and the loaded entry has not moved")
        assertEquals(1, engines.opened.size, "appending must not open anything")
        assertTrue(!first.closed, "nor close what was open")
    }

    @Test
    fun `appending to an empty player loads the first of what arrived`() = runTest {
        // A queue with a first entry and no engine is a player that looks broken.
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))

        player.enqueue(tracks(2))
        runCurrent()

        assertEquals(0, player.queueIndex.value)
        assertEquals(1, engines.opened.size)
    }

    // -- the end of a track ----------------------------------------------------

    @Test
    fun `a finished track hands over to the next one and is never called paused`() = runTest {
        // Ended maps to Ready and Ready reads as paused to every desktop protocol,
        // so publishing the gap between two files flipped a media widget's
        // transport to a play button and back on every boundary.
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(2)

        player.open(files)
        runCurrent()
        player.play()
        // Far enough for the poll to have turned the silent open into playing.
        // Pressing play does not write a state of its own: it starts the engine and
        // the loop reports what it finds, so watching before that tick would record
        // the Ready the open left behind and call it the gap.
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()
        assertTrue(player.state.value is PlaybackState.Playing, "the first track is running")

        val seen = mutableListOf<PlaybackState>()
        val watch = backgroundScope.launch { player.state.collect { seen += it } }
        runCurrent()

        engines.opened[0].state = VideoPlayer.State.Ended
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()

        assertEquals(2, engines.opened.size, "the next entry has to be opened")
        assertEquals(files[1], engines.opened[1].file)
        assertEquals(1, player.queueIndex.value)
        assertTrue(engines.opened[0].closed, "and the finished one released")
        assertTrue(
            seen.none { it is PlaybackState.Ready },
            "the gap between two files belongs to neither: $seen",
        )
        watch.cancel()
    }

    @Test
    fun `the end of a queue stops where the mode says it stops`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(2)

        player.open(files)
        runCurrent()
        player.playAt(1)
        runCurrent()
        assertEquals(1, player.queueIndex.value)

        engines.opened.last().state = VideoPlayer.State.Ended
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()

        assertEquals(2, engines.opened.size, "Off does not wrap")
        assertEquals(1, player.queueIndex.value)
    }

    @Test
    fun `the whole-queue mode wraps at the end instead of stopping`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(2)

        player.open(files)
        runCurrent()
        player.setRepeat(RepeatMode.Queue)
        player.playAt(1)
        runCurrent()

        engines.opened.last().state = VideoPlayer.State.Ended
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()

        assertEquals(0, player.queueIndex.value, "round to the front")
        assertEquals(files[0], engines.opened.last().file)
    }

    @Test
    fun `repeating one track rewinds the engine rather than reopening the file`() = runTest {
        // The engine is still alive at the end of a track, and rewinding it keeps
        // the decode thread and the device, so the loop is seamless.
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))

        player.open(tracks(1))
        runCurrent()
        player.setRepeat(RepeatMode.One)
        player.play()
        runCurrent()

        val engine = engines.opened.single()
        engine.state = VideoPlayer.State.Ended
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()

        assertEquals(1, engines.opened.size, "nothing is reopened")
        assertTrue(!engine.closed, "and nothing is released")
        assertTrue(engine.seeks.contains(0L), "the same engine goes back to the start")
    }

    // -- dropping an entry -----------------------------------------------------

    @Test
    fun `dropping an entry before the loaded one moves the index and not the engine`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(3)

        player.open(files)
        runCurrent()
        player.playAt(2)
        runCurrent()
        val playing = engines.opened.last()

        player.removeAt(0)
        runCurrent()

        assertEquals(1, player.queueIndex.value, "the loaded entry slid down with the list")
        assertEquals(files.drop(1), player.queue.value)
        assertTrue(!playing.closed, "and the engine was not disturbed")
    }

    @Test
    fun `dropping the loaded entry hands the engine to what took its place`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(3)

        player.open(files)
        runCurrent()
        val first = engines.opened.single()

        player.removeAt(0)
        runCurrent()

        assertEquals(0, player.queueIndex.value)
        assertEquals(files[1], engines.opened.last().file, "whatever slid into the slot")
        assertTrue(first.closed)
    }

    @Test
    fun `emptying the queue leaves the player idle and holding nothing`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))

        player.open(tracks(1))
        runCurrent()
        player.removeAt(0)
        runCurrent()

        assertEquals(PlaybackState.Idle, player.state.value)
        assertEquals(-1, player.queueIndex.value)
        assertTrue(player.queue.value.isEmpty())
        assertNull(player.track.value)
        assertTrue(engines.opened.single().closed)
    }

    // -- scrubbing into a track that finished -----------------------------------

    @Test
    fun `a scrub into a released track reopens it silent and stopped`() = runTest {
        // The poll loop drops the engine of a track that ran out, so a scrub back
        // into it has to build one. Silencing alone left it running inaudibly with
        // its position climbing, which the transport reported as playing.
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))

        player.open(tracks(1))
        runCurrent()
        player.play()
        runCurrent()

        engines.opened.single().state = VideoPlayer.State.Ended
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()
        assertTrue(engines.opened.single().closed, "the finished track was released")

        player.seek(45_000L)
        advanceTimeBy(POLL_SETTLE_MS)
        runCurrent()

        val reopened = engines.opened.last()
        assertEquals(2, engines.opened.size, "the scrub had to build an engine")
        assertEquals(0f, reopened.lastVolume, "and it must not be audible")
        assertTrue(reopened.paused, "nor running")
        assertEquals(listOf(45_000L * 1_000_000L), reopened.seeks, "milliseconds in, nanoseconds out")
    }

    @Test
    fun `a seek past the end of the container lands at the end and not beyond it`() = runTest {
        val engines = FakeEngines()
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))

        player.open(tracks(1))
        runCurrent()
        player.seek(999_000L)
        runCurrent()

        val engine = engines.opened.single()
        assertEquals(listOf(engine.durationNanos), engine.seeks)
    }

    // -- a file that will not open ----------------------------------------------

    @Test
    fun `an engine that refuses reports the failure and holds nothing`() = runTest {
        val engines = FakeEngines()
        engines.refuse = { IllegalStateException("no decoder") }
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(1)

        player.open(files)
        runCurrent()

        assertEquals(PlaybackState.Error(files[0], AudioError.OpenFailed), player.state.value)
        assertTrue(engines.opened.isEmpty())
    }

    @Test
    fun `a natives failure is a refusal rather than a crash`() = runTest {
        // It arrives as an Error and not an Exception, so the narrower catch has to
        // name it or a launcher whose media libraries will not load takes a press of
        // play as a crash.
        val engines = FakeEngines()
        engines.refuse = { UnsatisfiedLinkError("no libavcodec") }
        val player = AudioPlayer(scope = backgroundScope, engines = engines, engine = StandardTestDispatcher(testScheduler))
        val files = tracks(1)

        player.open(files)
        runCurrent()

        assertEquals(PlaybackState.Error(files[0], AudioError.OpenFailed), player.state.value)
    }

    // -- what the queue remembers ------------------------------------------------

    @Test
    fun `a restored entry whose file has gone takes its place with it`() = runTest {
        val files = tracks(2)
        Files.delete(files[0])
        val player = AudioPlayer(
            scope = backgroundScope,
            initialQueue = files,
            initialIndex = 1,
            engines = FakeEngines(),
            engine = StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        assertEquals(listOf(files[1]), player.queue.value, "a row that cannot play is not a row")
        assertEquals(0, player.queueIndex.value, "and the index follows what is left")
    }

    @Test
    fun `a restored queue is named and ready without opening anything`() = runTest {
        // The engine is what costs a decode thread and a device, and play already
        // opens on demand, so a launch comes back to the track that was there and
        // pays for it when somebody presses play.
        val engines = FakeEngines()
        val files = tracks(2)
        val player = AudioPlayer(
            scope = backgroundScope,
            initialQueue = files,
            initialIndex = 1,
            engines = engines,
            engine = StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        assertEquals(files[1], player.state.value.file)
        assertTrue(engines.opened.isEmpty(), "restoring must not open a file")

        player.play()
        runCurrent()
        assertEquals(1, engines.opened.size, "pressing play is what pays for it")
    }

    private companion object {
        /** Comfortably past a poll or two, so the loop has acted on a state change. */
        const val POLL_SETTLE_MS = 1_000L
    }
}
