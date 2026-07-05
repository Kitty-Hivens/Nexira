package hivens.ui.audio

import dev.hivens.skinema.player.VideoPlayer
import hivens.ui.diag.SkinemaGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path

// In-process audio playback for the MusicPlayerWidget, backed by Skinema
// (FFmpeg via Panama, audio = true). Plays mp3 / ogg / flac / opus / vorbis /
// aac / wav and more; the audio device masters the player's clock. One track
// at a time -- opening a new file closes the previous player.
//
// Every engine touch (open/play/pause/stop/setVolume) and the state poll loop
// run on a single-thread dispatcher [engine], confining the mutable fields to
// one thread: no locking, no torn reads, and -- because Skinema's close()
// blocks up to five seconds joining the decode thread -- no UI-thread freeze.
// The public methods are fire-and-forget; widgets observe [state] / [volume].
class AudioPlayer(private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(AudioPlayer::class.java)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    // Serializes engine ops + the poll loop onto one IO thread. limitedParallelism(1)
    // gives a confinement queue without owning a dedicated thread.
    private val engine = Dispatchers.IO.limitedParallelism(1)

    // All four are confined to [engine] -- only ever touched inside a
    // launch(engine) { } below.
    private var player: VideoPlayer? = null
    private var currentFile: Path? = null
    private var pollJob: Job? = null
    // Skinema represents both "opened, never played" and "played then paused"
    // as State.Paused; this carries the distinction the UI needs (Ready vs
    // Paused -- the latter enables the stop button, the former does not).
    private var started = false

    fun open(file: Path) {
        scope.launch(engine) {
            closeCurrent()
            currentFile = file
            started = false
            if (!SkinemaGate.enabled) {
                _state.value = PlaybackState.Error(file, AudioError.OpenFailed)
                return@launch
            }
            val p = try {
                VideoPlayer(path = file, loop = false, audio = true)
            } catch (e: Exception) {
                log.error("Failed to open audio file {}", file, e)
                _state.value = PlaybackState.Error(file, AudioError.OpenFailed)
                return@launch
            }
            // Assign before touching the player so a failure past this point
            // still closes it (closeCurrent on the next open/stop) -- never an
            // orphaned decode thread.
            player = p
            // Skinema opens and starts playing on its own thread. Hold it
            // silent until the user hits play: volume 0 + pause are queued
            // before the first audible buffer, so opening a track makes no
            // sound. play() restores the real volume.
            p.setVolume(0f)
            p.pause()
            _state.value = PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L)
            startPolling()
        }
    }

    fun play() {
        scope.launch(engine) {
            val p = player ?: return@launch
            started = true
            p.setVolume(_volume.value)
            // A finished track is revived by a seek (resume only un-pauses); a
            // paused/opened one just resumes from where it stands.
            if (p.state == VideoPlayer.State.Ended) p.seek(0L) else p.resume()
        }
    }

    fun pause() {
        scope.launch(engine) { player?.pause() }
    }

    fun stop() {
        scope.launch(engine) {
            closeCurrent()
            val file = currentFile
            _state.value = if (file != null) {
                PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L)
            } else {
                PlaybackState.Idle
            }
        }
    }

    // Linear 0..1. Reflected on the flow immediately for the UI; held off the
    // engine until the first play() so the silent open is not broken by a
    // volume change during the Ready window.
    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _volume.value = clamped
        scope.launch(engine) { if (started) player?.setVolume(clamped) }
    }

    // Confined to [engine]. cancelAndJoin guarantees the poll loop has stopped
    // before the player is closed and the field nulled, so no poll iteration
    // writes a stale state afterward.
    private suspend fun closeCurrent() {
        pollJob?.cancelAndJoin()
        pollJob = null
        player?.close()
        player = null
        started = false
    }

    private fun startPolling() {
        pollJob = scope.launch(engine) {
            while (isActive) {
                val p = player ?: break
                val file = currentFile ?: break
                val st = p.state
                _state.value = mapPlaybackState(
                    file    = file,
                    st      = st,
                    started = started,
                    posMs   = p.positionNanos() / 1_000_000L,
                    durMs   = (p.durationNanos ?: 0L) / 1_000_000L,
                )
                // A failed track is terminal until the next open(); stop
                // spinning the loop on it.
                if (st is VideoPlayer.State.Failed) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
    }
}

/**
 * Maps Skinema's player state plus the [started] flag to a [PlaybackState].
 * Pure -- the engine bridge's only branching, tested without natives.
 *
 * Skinema has no audio device error: a machine without one degrades to silent
 * playback, never [VideoPlayer.State.Failed]. A failure is therefore an open /
 * decode problem on the file, mapped to [AudioError.OpenFailed].
 */
internal fun mapPlaybackState(
    file: Path,
    st: VideoPlayer.State,
    started: Boolean,
    posMs: Long,
    durMs: Long,
): PlaybackState = when (st) {
    VideoPlayer.State.Opening -> PlaybackState.Ready(file, positionMs = 0L, durationMs = durMs)
    VideoPlayer.State.Playing -> PlaybackState.Playing(file, posMs, durMs)
    VideoPlayer.State.Seeking -> PlaybackState.Playing(file, posMs, durMs)
    VideoPlayer.State.Paused ->
        if (started) PlaybackState.Paused(file, posMs, durMs)
        else PlaybackState.Ready(file, posMs, durMs)
    VideoPlayer.State.Ended -> PlaybackState.Ready(file, positionMs = 0L, durationMs = durMs)
    is VideoPlayer.State.Failed -> PlaybackState.Error(file, AudioError.OpenFailed)
    VideoPlayer.State.Closed -> PlaybackState.Idle
}

sealed class PlaybackState {
    abstract val file: Path?

    object Idle : PlaybackState() {
        override val file: Path? = null
    }

    data class Ready(
        override val file: Path,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Playing(
        override val file: Path,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Paused(
        override val file: Path,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Error(
        override val file: Path,
        val reason: AudioError,
    ) : PlaybackState()
}

enum class AudioError { UnsupportedFormat, OpenFailed, DeviceBusy, PlaybackFailed }
