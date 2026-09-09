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
import kotlin.time.Duration.Companion.milliseconds

// In-process audio playback for the player widgets, backed by Skinema
// (FFmpeg via Panama, audio = true). Plays mp3 / ogg / flac / opus / vorbis /
// aac / wav and more; the audio device masters the player's clock.
//
// One ENGINE at a time, but a queue of files above it: the engine holds a decode
// thread and the audio device, so only the entry being played is open, and the
// rest of the queue is a list of paths. Reaching the end of a track loads the
// next entry rather than reopening the same one, which is what makes
// [RepeatMode.Queue] mean something.
//
// Every engine touch (open/play/pause/stop/setVolume) and the state poll loop
// run on a single-thread dispatcher [engine], confining the mutable fields to
// one thread: no locking, no torn reads, and -- because Skinema's close()
// blocks while it joins the decode thread -- no UI-thread freeze.
// The public methods are fire-and-forget; widgets observe [state] / [volume].
class AudioPlayer(
    private val scope: CoroutineScope,
    initialVolume: Float = 1.0f,
    /**
     * Where a settled volume goes. Called off the engine thread, debounced, so a
     * drag across the whole track is one write and not one per frame.
     */
    private val persistVolume: (Float) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(AudioPlayer::class.java)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(initialVolume.coerceIn(0f, 1f))
    val volume: StateFlow<Float> = _volume.asStateFlow()
    private var volumeWrite: Job? = null

    private val _repeat = MutableStateFlow(RepeatMode.Off)

    /**
     * What happens when the track runs out.
     *
     * Not passed to the engine as Skinema's own `loop` flag, even though it has
     * one: that is fixed when the player is constructed, so changing the mode
     * mid-track would mean reopening the file and losing the position. The poll
     * loop honours this instead, which makes the mode switchable while a track
     * plays and keeps one decision in one place.
     *
     * The mode governs what happens at the END of the queue, not between its
     * entries: a queue always steps to its next entry, and the mode decides
     * whether the end wraps ([RepeatMode.Queue]), stops ([RepeatMode.Off]), or
     * never arrives because the current entry repeats ([RepeatMode.One]).
     */
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    fun setRepeat(mode: RepeatMode) {
        _repeat.value = mode
    }

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

    private val _queue = MutableStateFlow<List<Path>>(emptyList())

    /**
     * What is loaded and what follows it, in order. Paths rather than tracks: a
     * queue of forty entries would mean forty engines to read forty sets of tags,
     * so a consumer drawing the queue draws file names and only the entry being
     * played has [track] behind it.
     */
    val queue: StateFlow<List<Path>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)

    /** Which entry of [queue] is loaded, or -1 when nothing is. */
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    // Confined to [engine] -- only ever touched inside a launch(engine) { } below.
    // The queue flows are written from there too; they are flows rather than plain
    // fields only because the UI reads them.
    private var player: VideoPlayer? = null
    private var pollJob: Job? = null
    // Skinema represents both "opened, never played" and "played then paused"
    // as State.Paused; this carries the distinction the UI needs (Ready vs
    // Paused -- the latter enables the stop button, the former does not).
    private var started = false

    /** The entry the engine is on, derived rather than stored so the two cannot part. */
    private val loadedFile: Path? get() = _queue.value.getOrNull(_queueIndex.value)

    /** One file: a queue of one, so every path through the player is the same path. */
    fun open(file: Path) = open(listOf(file))

    /**
     * Replaces the queue with [files] and loads the first of them, silent, the way
     * a single open has always behaved: the user pressed a picker, not Play.
     *
     * Replaces rather than appends because this is what the picker does, and a
     * picker that grew the queue every time would make "open" mean "open plus
     * everything I opened this session". [enqueue] is the other verb.
     */
    fun open(files: List<Path>) {
        if (files.isEmpty()) return
        scope.launch(engine) {
            log.info("Audio open requested: {} file(s)", files.size)
            _queue.value = files
            loadAt(0, autoplay = false)
        }
    }

    /**
     * Appends [files] to the queue without disturbing what is playing. With nothing
     * loaded it behaves as [open] on the appended entries, since a queue with a
     * first entry and no engine is a player that looks broken.
     */
    fun enqueue(files: List<Path>) {
        if (files.isEmpty()) return
        scope.launch(engine) {
            val hadNothing = _queueIndex.value < 0
            val start = _queue.value.size
            _queue.value = _queue.value + files
            if (hadNothing) loadAt(start, autoplay = false)
        }
    }

    /** Loads [index] and plays it. For a click on a queue row. */
    fun playAt(index: Int) {
        scope.launch(engine) {
            if (index in _queue.value.indices) loadAt(index, autoplay = true)
        }
    }

    /**
     * Steps the queue, keeping whether it was sounding: skipping while paused
     * lands paused on the next entry, and skipping while playing keeps playing.
     * Does nothing at an end the mode does not wrap.
     */
    fun skipToNext() = step { nextQueueIndex(_queue.value.size, _queueIndex.value, _repeat.value) }

    fun skipToPrevious() = step { previousQueueIndex(_queue.value.size, _queueIndex.value, _repeat.value) }

    private fun step(pick: () -> Int?) {
        scope.launch(engine) {
            val target = pick() ?: return@launch
            loadAt(target, autoplay = started)
        }
    }

    /**
     * Drops one entry. Removing the entry being played moves to what took its
     * place, and emptying the queue leaves the player idle rather than holding a
     * path that is no longer in it.
     */
    fun removeAt(index: Int) {
        scope.launch(engine) {
            val edit = removeFromQueue(_queue.value, _queueIndex.value, index) ?: return@launch
            _queue.value = edit.queue
            when {
                edit.queue.isEmpty() -> {
                    closeCurrent()
                    _queueIndex.value = -1
                    _track.value = null
                    _state.value = PlaybackState.Idle
                }
                edit.reload -> loadAt(edit.index, autoplay = started)
                else -> _queueIndex.value = edit.index
            }
        }
    }

    /**
     * Opens [index] on the engine, replacing whatever was open.
     *
     * [autoplay] is what an advance within the queue needs: a silent open is right
     * for a picker, because the user asked for a file rather than for sound, but
     * the second entry of a queue arrives while the first one is still audible and
     * must not go quiet. Confined to [engine] like every other engine touch.
     */
    private suspend fun loadAt(index: Int, autoplay: Boolean) {
        val file = _queue.value.getOrNull(index) ?: return
        closeCurrent()
        _queueIndex.value = index
        started = autoplay
        // Metadata belongs to the file, so this is the only place it is dropped: a
        // track that ended, or was stopped, is still the track that is loaded.
        _track.value = null
        // Assign before touching the player so a failure past this point still
        // closes it (closeCurrent on the next load/stop) -- never an orphaned
        // decode thread.
        val p = openPlayer(file) ?: return
        player = p
        if (autoplay) {
            p.setVolume(_volume.value)
        } else {
            // Skinema opens and starts playing on its own thread. Hold it silent
            // until the user hits play: volume 0 + pause are queued before the
            // first audible buffer, so opening a track makes no sound.
            p.setVolume(0f)
            p.pause()
        }
        _state.value = if (autoplay) {
            PlaybackState.Playing(file, positionMs = 0L, durationMs = 0L)
        } else {
            PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L)
        }
        startPolling()
    }

    fun play() {
        scope.launch(engine) {
            val file = loadedFile ?: return@launch
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
            openFailed(file, e)
        } catch (e: LinkageError) {
            // A natives bundle that is missing, or from another FFmpeg line,
            // fails as an Error rather than an Exception: the catch above never
            // saw it, so a launcher whose media libraries will not load took the
            // press of Play as a crash instead of a track that will not open.
            // Narrower than Throwable on purpose: an OutOfMemoryError here is
            // not a file that failed to open.
            openFailed(file, e)
        }
    }

    private fun openFailed(file: Path, cause: Throwable): VideoPlayer? {
        log.error("Failed to open audio file {}", file, cause)
        _state.value = PlaybackState.Error(file, AudioError.OpenFailed)
        return null
    }

    fun pause() {
        scope.launch(engine) { player?.pause() }
    }

    /**
     * Jump to [positionMs] in the loaded track.
     *
     * Skinema counts in nanoseconds, so the millisecond the UI works in is
     * converted here rather than at every call site. A track that ran to its end
     * was released by the poll loop, and it is still the track that is loaded, so
     * a seek into it re-opens the file exactly as [play] does -- the alternative
     * is a scrubber that silently does nothing once the track finishes, which
     * from the user's side is the same as a broken control.
     *
     * The position is clamped into the container's own duration where one is
     * known: a drag to the very end of a bar is a request for the end of the
     * track, not for a position past it.
     */
    fun seek(positionMs: Long) {
        scope.launch(engine) {
            val file = loadedFile ?: return@launch
            val p = player ?: openPlayer(file)?.also {
                player = it
                it.setVolume(if (started) _volume.value else 0f)
                startPolling()
            } ?: return@launch
            val durationNanos = p.durationNanos
            val wanted = (positionMs.coerceAtLeast(0L)) * 1_000_000L
            p.seek(if (durationNanos != null && durationNanos > 0L) wanted.coerceAtMost(durationNanos) else wanted)
        }
    }

    fun stop() {
        scope.launch(engine) {
            closeCurrent()
            val file = loadedFile
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
        // A slider drag reports on every pointer frame, and the settings file is a
        // read-modify-write of the whole document: the disk sees one write once the
        // hand stops rather than sixty on the way across the track.
        volumeWrite?.cancel()
        volumeWrite = scope.launch {
            delay(VOLUME_WRITE_DELAY_MS.milliseconds)
            withContext(Dispatchers.IO) { runCatching { persistVolume(clamped) } }
        }
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
                val file = loadedFile ?: break
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
                if (st == VideoPlayer.State.Ended) {
                    // Repeating ONE track is a seek, not a reopen: the engine is
                    // still alive at this point and rewinding it keeps the decode
                    // thread and the audio device, so the loop is seamless and the
                    // mode can change while the track plays.
                    if (_repeat.value == RepeatMode.One) {
                        p.seek(0L)
                        p.resume()
                        delay(POLL_INTERVAL_MS.milliseconds)
                        continue
                    }
                    val next = nextQueueIndex(_queue.value.size, _queueIndex.value, _repeat.value)
                    // A finished track holds a decode thread and the audio device
                    // open for nothing either way. Drop them WITHOUT closeCurrent(),
                    // which would join the very job this runs on.
                    releaseEngine()
                    if (next != null) {
                        // A separate coroutine for the same reason: the advance
                        // closes and opens an engine, and it cannot do that from
                        // inside the job it has to join. [engine] is single-lane, so
                        // it queues behind this one rather than racing it.
                        scope.launch(engine) { loadAt(next, autoplay = true) }
                    }
                    break
                }
                delay(POLL_INTERVAL_MS.milliseconds)
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
        const val VOLUME_WRITE_DELAY_MS = 500L
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

/**
 * The entry a finished track hands over to, or null when the queue is done.
 *
 * Pure, and beside [mapPlaybackState] for the same reason: it is the whole of the
 * queue's behaviour at an edge, it has four cases that are easy to get backwards,
 * and testing it through the engine would need FFmpeg natives on the test path.
 *
 * [RepeatMode.One] never reaches here -- the poll loop rewinds the current entry
 * instead -- but it is answered anyway, as itself, so a caller that asks under that
 * mode gets the current entry rather than a step it did not want.
 */
internal fun nextQueueIndex(size: Int, index: Int, repeat: RepeatMode): Int? = when {
    size <= 0 || index < 0 -> null
    repeat == RepeatMode.One -> index
    index + 1 < size -> index + 1
    // Round the queue, which for a queue of one is the same entry again: that is
    // what the mode said, and it is what looping a single track used to do.
    repeat == RepeatMode.Queue -> 0
    else -> null
}

/**
 * The entry a skip-back lands on. Stops at the front under every mode but
 * [RepeatMode.Queue], which wraps to the end: a queue the user can walk forward
 * round the loop but not backward round it is a loop in one direction only.
 */
internal fun previousQueueIndex(size: Int, index: Int, repeat: RepeatMode): Int? = when {
    size <= 0 || index < 0 -> null
    repeat == RepeatMode.One -> index
    index - 1 >= 0 -> index - 1
    repeat == RepeatMode.Queue -> size - 1
    else -> null
}

/** What a queue looks like after one entry is dropped out of it. */
internal data class QueueEdit(
    val queue: List<Path>,
    val index: Int,
    /** True when the dropped entry was the one loaded, so the engine has to move. */
    val reload: Boolean,
)

/**
 * Drops [removeAt] from [queue], and says where the loaded entry went.
 *
 * Three cases and only one of them is obvious. Removing something BEFORE the
 * loaded entry shifts it down by one without touching the engine, which is the
 * case that silently plays the wrong row if the index is not moved. Removing the
 * loaded entry itself hands the engine to whatever slid into that slot, or to the
 * new last entry when it was the tail. Removing something after it changes
 * nothing. Null means the request named an entry that is not there.
 */
internal fun removeFromQueue(queue: List<Path>, index: Int, removeAt: Int): QueueEdit? {
    if (removeAt !in queue.indices) return null
    val shortened = queue.toMutableList().also { it.removeAt(removeAt) }
    return when {
        shortened.isEmpty() -> QueueEdit(shortened, -1, reload = false)
        removeAt < index -> QueueEdit(shortened, index - 1, reload = false)
        removeAt > index -> QueueEdit(shortened, index, reload = false)
        else -> QueueEdit(shortened, index.coerceAtMost(shortened.lastIndex), reload = true)
    }
}

enum class AudioError { UnsupportedFormat, OpenFailed, DeviceBusy, PlaybackFailed }

/**
 * What to do at the end of a track.
 *
 * Three states rather than a boolean, because the two useful loops are different
 * questions: repeat this one thing, or go round the whole set. Off is the third
 * and it is the default -- the launcher had no mode at all and every engine was
 * built with looping disabled, which is the absence of a decision rather than a
 * decision.
 */
enum class RepeatMode {
    Off,
    One,

    /** Round the queue. Behaves as [One] until a queue exists to step. */
    Queue,
}
