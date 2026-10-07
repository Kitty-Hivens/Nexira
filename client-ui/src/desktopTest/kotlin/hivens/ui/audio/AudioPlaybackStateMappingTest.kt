package hivens.ui.audio

import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine bridge's only branching: Skinema state + the started flag ->
 * [PlaybackState]. Pure, so it pins the Ready/Paused split and the Ended reset
 * without natives or an audio device.
 */
class AudioPlaybackStateMappingTest {

    private val file: Path = Path.of("/music/track.mp3")

    @Test
    fun openingShowsReadyWithDuration() {
        assertEquals(
            PlaybackState.Ready(file, positionMs = 0, durationMs = 1000),
            mapPlaybackState(file, VideoPlayer.State.Opening, started = false, posMs = 0, durMs = 1000),
        )
    }

    @Test
    fun playingCarriesPositionAndDuration() {
        assertEquals(
            PlaybackState.Playing(file, positionMs = 1500, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Playing, started = true, posMs = 1500, durMs = 3000),
        )
    }

    @Test
    fun seekingPresentsAsPlaying() {
        assertEquals(
            PlaybackState.Playing(file, positionMs = 500, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Seeking, started = true, posMs = 500, durMs = 3000),
        )
    }

    @Test
    fun playingBeforeFirstPlayIsReady() {
        // Skinema starts as soon as it is constructed and takes a pause on its own
        // thread, so an engine opened for a file nobody asked to hear is briefly
        // playing while silenced. Reported as playing, the transport offered a
        // pause button over no sound for a tick of every open and every scrub into
        // a finished track.
        assertEquals(
            PlaybackState.Ready(file, positionMs = 0, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Playing, started = false, posMs = 0, durMs = 3000),
        )
    }

    @Test
    fun seekingBeforeFirstPlayIsReady() {
        // The same window, reached through the other sounding state: a scrub into a
        // track that had finished re-opens the engine and seeks it at once.
        assertEquals(
            PlaybackState.Ready(file, positionMs = 45_000, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Seeking, started = false, posMs = 45_000, durMs = 3000),
        )
    }

    @Test
    fun pausedBeforeFirstPlayIsReady() {
        assertEquals(
            PlaybackState.Ready(file, positionMs = 0, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Paused, started = false, posMs = 0, durMs = 3000),
        )
    }

    @Test
    fun pausedAfterPlayIsPaused() {
        assertEquals(
            PlaybackState.Paused(file, positionMs = 1200, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Paused, started = true, posMs = 1200, durMs = 3000),
        )
    }

    @Test
    fun endedResetsToReadyKeepingDuration() {
        assertEquals(
            PlaybackState.Ready(file, positionMs = 0, durationMs = 3000),
            mapPlaybackState(file, VideoPlayer.State.Ended, started = true, posMs = 3000, durMs = 3000),
        )
    }

    @Test
    fun failedMapsToOpenError() {
        assertEquals(
            PlaybackState.Error(file, AudioError.OpenFailed),
            mapPlaybackState(file, VideoPlayer.State.Failed(RuntimeException("boom")), started = false, posMs = 0, durMs = 0),
        )
    }

    @Test
    fun closedIsIdle() {
        assertEquals(
            PlaybackState.Idle,
            mapPlaybackState(file, VideoPlayer.State.Closed, started = true, posMs = 0, durMs = 0),
        )
    }
}
