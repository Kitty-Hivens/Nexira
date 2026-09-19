package hivens.ui.audio

import hivens.ui.widgets.services.MusicPlayerService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Handing the session over, and what a widget sees while it happens.
 *
 * The router runs on backgroundScope rather than the test scope: its flows are
 * started eagerly and never complete, and runTest awaits the scope's children, so
 * handing it the test scope is five sixty-second timeouts rather than five tests.
 *
 * The router is the one part of the link that is testable without a decoder, so it
 * is where the behaviour is pinned: which object answers, that a verb reaches
 * whoever owns the session at the moment it is pressed rather than whoever owned it
 * when the widget composed, and that the music player keeps everything it had while
 * somebody else is being asked.
 */
// runCurrent drives the scheduler by hand, which is the instrument here: a
// handover is only observable between two turns of the clock.
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRouterTest {

    /** A [MusicPlayerService] that records what it was told and reports what it is set to. */
    private class Spy(name: String) : MusicPlayerService {
        val calls = mutableListOf<String>()

        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state.asStateFlow()
        private val _volume = MutableStateFlow(0f)
        override val volume: StateFlow<Float> = _volume.asStateFlow()
        private val _track = MutableStateFlow<TrackInfo?>(null)
        override val track: StateFlow<TrackInfo?> = _track.asStateFlow()
        private val _queue = MutableStateFlow<List<Path>>(listOf(Path.of("/tmp/$name")))
        override val queue: StateFlow<List<Path>> = _queue.asStateFlow()
        private val _queueIndex = MutableStateFlow(0)
        override val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()
        private val _repeat = MutableStateFlow(RepeatMode.Off)
        override val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

        fun report(state: PlaybackState) { _state.value = state }
        fun report(level: Float) { _volume.value = level }

        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun setVolume(level: Float) { calls += "volume:$level" }
        override fun setRepeat(mode: RepeatMode) { calls += "repeat:$mode" }
        override fun open(files: List<Path>) { calls += "open:${files.size}" }
        override fun enqueue(files: List<Path>) { calls += "enqueue" }
        override fun playAt(index: Int) { calls += "playAt:$index" }
        override fun skipToNext() { calls += "next" }
        override fun skipToPrevious() { calls += "previous" }
        override fun removeAt(index: Int) { calls += "removeAt:$index" }
    }

    @Test
    fun `with nothing linked the music player is what answers`() = runTest {
        val music = Spy("music")
        val router = PlaybackRouter(backgroundScope, music, WallpaperSession(backgroundScope))
        runCurrent()

        assertEquals(listOf(Path.of("/tmp/music")), router.queue.value)
        router.play()
        assertEquals(listOf("play"), music.calls)
    }

    @Test
    fun `linking moves what is read and what is driven, in one step`() = runTest {
        val music = Spy("music")
        val wall = WallpaperSession(backgroundScope)
        val router = PlaybackRouter(backgroundScope, music, wall)
        runCurrent()

        router.setWallpaperOwns(true)
        runCurrent()

        // The wallpaper holds nothing while nothing is attached, which is what a
        // link switched on before a wallpaper plays looks like.
        assertEquals(emptyList(), router.queue.value)
        router.play()
        assertEquals(emptyList(), music.calls, "the music player must stop being asked, not be asked twice")
    }

    @Test
    fun `a verb reaches whoever owns the session when it is pressed`() = runTest {
        val music = Spy("music")
        val router = PlaybackRouter(backgroundScope, music, WallpaperSession(backgroundScope))
        runCurrent()

        // The window a captured reference gets wrong: the widget composed against
        // one owner and the person clicked after the handover.
        router.setWallpaperOwns(true)
        runCurrent()
        router.pause()
        router.setWallpaperOwns(false)
        runCurrent()
        router.pause()

        assertEquals(listOf("pause"), music.calls, "exactly the press made after the session came back")
    }

    @Test
    fun `the music player keeps its queue while somebody else is asked`() = runTest {
        val music = Spy("music")
        val router = PlaybackRouter(backgroundScope, music, WallpaperSession(backgroundScope))
        runCurrent()

        router.setWallpaperOwns(true)
        runCurrent()
        router.setWallpaperOwns(false)
        runCurrent()

        assertEquals(
            listOf(Path.of("/tmp/music")),
            router.queue.value,
            "unlinking has to come back to the track that was there, not to an empty player",
        )
        assertEquals(emptyList(), music.calls, "and the handover must not have driven it at all")
    }

    @Test
    fun `what the owner reports is what the contract reports`() = runTest {
        val music = Spy("music")
        val router = PlaybackRouter(backgroundScope, music, WallpaperSession(backgroundScope))
        runCurrent()

        music.report(0.42f)
        runCurrent()
        assertEquals(0.42f, router.volume.value)

        val playing = PlaybackState.Playing(Path.of("/tmp/music"), positionMs = 7_000, durationMs = 9_000)
        music.report(playing)
        runCurrent()
        assertEquals(playing, router.state.value)
    }
}
