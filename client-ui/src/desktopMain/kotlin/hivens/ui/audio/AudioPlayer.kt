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
import kotlinx.coroutines.withContext
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

    private val _track = MutableStateFlow<TrackInfo?>(null)

    /**
     * What the loaded file says it is -- tags and cover art, read once per open.
     * Separate from [state] on purpose: the transport ticks five times a second
     * and this changes once a track, so a renderer that only draws the name and
     * the picture is not woken by the position.
     *
     * Null means nothing is loaded, never "this track has no metadata" -- a file
     * without tags still resolves to a title from its name.
     */
    val track: StateFlow<TrackInfo?> = _track.asStateFlow()

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
            log.info("Audio open requested: {}", file)
            closeCurrent()
            currentFile = file
            started = false
            // Metadata belongs to the file, so this is the only place it is
            // dropped: a track that ended, or was stopped, is still the track
            // that is loaded.
            _track.value = null
            // Assign before touching the player so a failure past this point
            // still closes it (closeCurrent on the next open/stop) -- never an
            // orphaned decode thread.
            val p = openPlayer(file) ?: return@launch
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
            val file = currentFile ?: return@launch
            // A track that ran to its end was released (see the poll loop), so
            // playing it again means opening it again -- from the user's side
            // this is still "press play on the track that is loaded".
            val p = player ?: openPlayer(file)?.also { player = it; startPolling() } ?: return@launch
            started = true
            p.setVolume(_volume.value)
            // A finished track is revived by a seek (resume only un-pauses); a
            // paused/opened one just resumes from where it stands, and one just
            // re-opened is already playing and ignores both.
            if (p.state == VideoPlayer.State.Ended) p.seek(0L) else p.resume()
        }
    }

    /**
     * Constructs the engine for [file], reporting a refusal or a failed open on
     * [state]. Confined to [engine] like every other player touch.
     */
    private fun openPlayer(file: Path): VideoPlayer? {
        if (!SkinemaGate.enabled) {
            log.warn("Audio open refused: the skinema module is disabled")
            _state.value = PlaybackState.Error(file, AudioError.OpenFailed)
            return null
        }
        return try {
            VideoPlayer(path = file, loop = false, audio = true)
        } catch (e: Exception) {
            log.error("Failed to open audio file {}", file, e)
            _state.value = PlaybackState.Error(file, AudioError.OpenFailed)
            null
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
                // Once per file: null is cleared only by open(), and a file with
                // no tags still resolves to a title, so this cannot re-fire.
                if (_track.value == null && st != VideoPlayer.State.Opening) readMetadata(p, file)
                // A failed track is terminal until the next open(); stop
                // spinning the loop on it. Skinema opens on its own decode
                // thread, so the failure arrives here rather than out of the
                // constructor -- this is the only place the cause exists, and
                // every route to it collapses into one user-visible error.
                if (st is VideoPlayer.State.Failed) {
                    log.error("Audio playback failed for {}", file, st.cause)
                    break
                }
                // A track that ran out holds a decode thread and the audio
                // device open for nothing. Drop them and keep the file, so
                // play() opens it again -- and not through closeCurrent(),
                // which joins the very job this runs on.
                if (st == VideoPlayer.State.Ended) {
                    releaseEngine()
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Reads the track's tags and cover art, once, as soon as the player is past
     * Opening: skinema fills both on its own decode thread while the open
     * completes, so they cannot be read from [open]. The picture is decoded off
     * [engine] -- a several-megapixel cover would otherwise sit in front of
     * every transport command queued behind it.
     */
    private suspend fun readMetadata(p: VideoPlayer, file: Path) {
        val artwork = p.coverArt?.let { withContext(Dispatchers.Default) { decodeArtwork(it) } }
        _track.value = trackInfoFrom(p.tags, file, artwork)
    }

    /**
     * Drops the engine but keeps the loaded track. Called from the poll loop, so
     * unlike [closeCurrent] it must not join the job it is running on; the loop
     * breaks immediately after.
     */
    private fun releaseEngine() {
        pollJob = null
        player?.close()
        player = null
        started = false
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
