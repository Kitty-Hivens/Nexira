package hivens.module.pixelplayer

import dev.hivens.skinema.player.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** Where the playlist is and what the current track is doing. */
internal data class PlayerState(
    val tracks: List<Path> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val artwork: ByteArray? = null,
    val failed: Boolean = false,
) {
    val current: Path? get() = tracks.getOrNull(index)
    val fraction: Float get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    // [artwork] is compared by reference, which is what a data class does with an
    // array and what is wanted here: the array is replaced only when a track
    // opens, so identity already means "different cover" without walking
    // megabytes of JPEG on every 200ms poll.
}

/**
 * Playback for one folder of files, owned by this module.
 *
 * It does not reach into the launcher for an audio service on purpose: this
 * module exists to show that a widget can arrive from outside `client-ui` and
 * still work, and a module that borrows the trunk's engine has not shown that.
 *
 * Every engine touch and the poll loop run on one confined dispatcher, so the
 * mutable fields are safely published and the close of a decode thread never
 * lands on the drawing thread. Confinement is not enough on its
 * own for the compound operations -- see [gate].
 */
internal class PixelPlayer private constructor() {

    /**
     * The player's own scope, not the composition's.
     *
     * Borrowing `rememberCoroutineScope()` looked right and was not: teardown is
     * launched on disposal, and by then the composition's scope is being
     * cancelled, so the close never ran. The decode thread and the audio device
     * stayed open for the life of the process, and a widget that had scrolled
     * out of existence went on playing with nothing left on screen to stop it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val engine = Dispatchers.IO.limitedParallelism(1)

    /**
     * Serialises the compound operations, which confinement alone does not.
     *
     * `limitedParallelism(1)` gives visibility and ordering, but [close] suspends
     * while joining the poll job and the slot is released there. Two Next presses
     * then interleave: both read the same index, the second resumes inside its
     * own close and shuts the player the first had just opened, so two presses
     * advance one track and the audio device is opened and closed within
     * milliseconds.
     */
    private val gate = Mutex()

    private var player: VideoPlayer? = null
    private var pollJob: Job? = null

    /**
     * Whether the user wants sound, as distinct from whether sound is coming out.
     *
     * Stepping used to resume from `playing || player != null`, so Next on a
     * paused player started it, and two quick Nexts read the first track's
     * `Opening` state as not-playing and left the third one silent. Intent
     * survives both.
     */
    private var wantsPlayback = false

    /** How many mounted widgets are looking at this player. */
    private val views = java.util.concurrent.atomic.AtomicInteger(0)

    /** Replaces the playlist. Keeps playing if the current track is still in it. */
    fun setTracks(tracks: List<Path>) {
        scope.launch(engine) {
            // An unconfigured copy of the widget hands over an empty list on its
            // first composition. Taking that literally means dropping a second
            // player onto a surface, or navigating to a page that already has
            // one, stops whatever the configured one was playing. An empty list
            // is the absence of a request, not a request for silence.
            if (tracks.isEmpty() && _state.value.tracks.isNotEmpty()) return@launch
            gate.withLock {
                val keep = _state.value.current
                val keptIndex = tracks.indexOf(keep)
                if (keptIndex >= 0) {
                    _state.value = _state.value.copy(tracks = tracks, index = keptIndex)
                } else {
                    close()
                    _state.value = PlayerState(tracks = tracks, index = if (tracks.isEmpty()) -1 else 0)
                }
            }
        }
    }

    fun toggle() {
        scope.launch(engine) {
            gate.withLock {
            val p = player
            if (p == null) {
                openCurrent(autoPlay = true)
            } else if (_state.value.playing) {
                wantsPlayback = false
                p.pause()
            } else {
                wantsPlayback = true
                if (p.state == VideoPlayer.State.Ended) p.seek(0L) else p.resume()
            }
            }
        }
    }

    fun next() = step(+1)

    fun previous() {
        // The convention every player has: back restarts the track unless you
        // are already at its beginning, where it steps.
        scope.launch(engine) {
            gate.withLock {
                if (_state.value.positionMs > RESTART_WINDOW_MS) player?.seek(0L) else stepNow(-1)
            }
        }
    }

    /** Seek to a fraction of the track. The bar is discrete, so this arrives snapped. */
    fun seekTo(fraction: Float) {
        scope.launch(engine) {
            val duration = _state.value.durationMs
            if (duration <= 0L) return@launch
            val target = (fraction.coerceIn(0f, 1f) * duration).toLong()
            player?.seek(target * 1_000_000L, exact = true)
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    /**
     * A view of this player mounted or went away.
     *
     * Playback belongs to the widget for as long as some view of it exists, and
     * a view switch is a gap of milliseconds while a crash is a gap that never
     * closes. So the count is what decides, with a grace period long enough to
     * cover a remount and short enough that a launcher which fell into recovery
     * is not still playing music at whoever is reading the crash.
     *
     * The decrement is a plain counter rather than a coroutine on purpose: a
     * teardown launched on the scope that is being torn down never runs, which
     * is how this player used to survive its own disposal.
     */
    fun viewAttached() {
        views.incrementAndGet()
    }

    fun viewDetached() {
        if (views.decrementAndGet() > 0) return
        scope.launch {
            delay(NO_VIEW_GRACE_MS.milliseconds)
            if (views.get() == 0) withContext(engine) { close() }
        }
    }

    private fun step(delta: Int) {
        scope.launch(engine) { gate.withLock { stepNow(delta) } }
    }

    private suspend fun stepNow(delta: Int) {
        val s = _state.value
        if (s.tracks.isEmpty()) return
        val resume = wantsPlayback
        val next = ((s.index + delta) % s.tracks.size + s.tracks.size) % s.tracks.size
        close()
        _state.value = s.copy(index = next, positionMs = 0, durationMs = 0, title = "", artist = "", artwork = null, failed = false)
        if (resume) openCurrent(autoPlay = true)
    }

    private fun openCurrent(autoPlay: Boolean) {
        val file = _state.value.current ?: return
        val p = runCatching { VideoPlayer(path = file, loop = false, audio = true) }.getOrElse {
            _state.value = _state.value.copy(failed = true, playing = false)
            return
        }
        player = p
        wantsPlayback = autoPlay
        if (!autoPlay) p.pause()
        _state.value = _state.value.copy(failed = false, title = Playlist.titleOf(file))
        startPolling()
    }

    private fun startPolling() {
        pollJob = scope.launch(engine) {
            var metadataRead = false
            while (isActive) {
                val p = player ?: break
                val st = p.state
                if (!metadataRead && st != VideoPlayer.State.Opening) {
                    metadataRead = true
                    readMetadata(p)
                }
                _state.value = _state.value.copy(
                    playing = st == VideoPlayer.State.Playing,
                    positionMs = p.positionNanos() / 1_000_000L,
                    durationMs = (p.durationNanos ?: 0L) / 1_000_000L,
                    failed = st is VideoPlayer.State.Failed,
                )
                // A corrupt or unsupported file is one file, not the end of the
                // folder. Skipping it is what a queue does; halting turns one bad
                // track into a player that never plays again.
                if (st is VideoPlayer.State.Failed) { advanceFromPoll(); break }
                // A finished track hands over to the next one: a folder is a
                // queue, and stopping dead at every track end is not playing a
                // folder, it is playing one file repeatedly by hand.
                if (st == VideoPlayer.State.Ended) { advanceFromPoll(); break }
                delay(POLL_MS.milliseconds)
            }
        }
    }

    private fun readMetadata(p: VideoPlayer) {
        val tags = runCatching { p.tags }.getOrNull().orEmpty()
        val file = _state.value.current
        val fallback = file?.let { Playlist.titleOf(it) } ?: ""
        _state.value = _state.value.copy(
            // A tag that survives repair but is still wreckage reads as the
            // track's actual name, which is worse than the file name: the user
            // cannot tell a broken tag from a track that is genuinely called
            // that.
            title = tags.pick("title")?.takeIf { TagText.isLegible(it) } ?: fallback,
            artist = tags.pick("artist")?.takeIf { TagText.isLegible(it) }
                ?: tags.pick("album_artist")?.takeIf { TagText.isLegible(it) } ?: "",
            artwork = runCatching { p.coverArt }.getOrNull(),
        )
    }

    /** Container tags disagree about case between formats, so ask case-insensitively. */
    private fun Map<String, String>.pick(key: String): String? =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
            ?.let { TagText.repair(it) }

    private suspend fun close() {
        pollJob?.cancelAndJoin()
        pollJob = null
        dropEngine()
    }

    /**
     * Step to the next track from INSIDE the poll loop.
     *
     * [close] joins the poll job, and the poll loop is that job -- joining from
     * within cancels the caller and throws before the engine is closed, so the
     * finished track kept its decode thread and its audio device and nothing
     * ever started the next one. The loop breaks immediately after this, which
     * is what makes cancelling unnecessary here.
     */
    private fun advanceFromPoll() {
        pollJob = null
        dropEngine()
        val s = _state.value
        if (s.tracks.isEmpty()) return
        val next = (s.index + 1) % s.tracks.size
        _state.value = s.copy(index = next, positionMs = 0, durationMs = 0, title = "", artist = "", artwork = null, failed = false, playing = false)
        openCurrent(autoPlay = true)
    }

    /** Closes the engine and forgets it. Does not touch the poll job. */
    private fun dropEngine() {
        player?.close()
        player = null
        _state.value = _state.value.copy(playing = false)
    }

    companion object {
        private const val POLL_MS = 200L
        private const val RESTART_WINDOW_MS = 3_000L
        private const val NO_VIEW_GRACE_MS = 1_500L

        /**
         * One player for the process, the way the launcher's own audio is one
         * player: two copies of a widget are two views of the same playback, not
         * two things playing at once. It also means nothing is released when a
         * view is switched away from -- there is no per-instance engine to leak.
         *
         * The folder is whichever mounted instance configured it last. Two
         * instances pointed at different folders is a question a music player
         * does not have an answer to, and inventing one would be worse than the
         * plain rule.
         */
        val shared: PixelPlayer by lazy { PixelPlayer() }
    }
}
