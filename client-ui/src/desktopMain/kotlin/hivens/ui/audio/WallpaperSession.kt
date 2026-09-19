package hivens.ui.audio

import dev.hivens.skinema.player.VideoPlayer
import hivens.ui.widgets.services.MusicPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * The wallpaper, as something that plays.
 *
 * A second thing that can answer [MusicPlayerService], so the clip on the wall can
 * be what the player widgets read and drive. It owns no player of its own: the
 * background composes one and hands it over, because the player's lifetime belongs
 * to the settings that built it (the file, whether it sounds, how it decodes) and
 * not to anything here.
 *
 * ## The queue is one entry, and that decides half the surface
 *
 * A wallpaper is one clip. So [queue] is that clip and [queueIndex] is zero, which
 * is what lets a transport print a name and draw a measure without knowing what it
 * is looking at, and every verb that moves between entries does nothing. That is
 * not a stub. It is the same answer [AudioPlayer] already gives at an end its
 * repeat mode does not wrap, so a widget offering skip buttons unconditionally gets
 * controls that are simply inert rather than controls that lie.
 *
 * ## Pause stops the picture, and that is the point
 *
 * Unlinked, a wallpaper is decoration with a soundtrack and its transport touches
 * the loudness alone. Linked, it is a track, and a track put on pause is paused:
 * keeping the animation running over stopped audio is the incoherent half. What it
 * leaves on screen is the last decoded frame, because the frame image is only
 * replaced by a decode, so a player that stops decoding keeps handing over the one
 * it has.
 */
class WallpaperSession(
    private val scope: CoroutineScope,
) : MusicPlayerService {

    private val log = LoggerFactory.getLogger(WallpaperSession::class.java)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _track = MutableStateFlow<TrackInfo?>(null)
    override val track: StateFlow<TrackInfo?> = _track.asStateFlow()

    private val _queue = MutableStateFlow<List<Path>>(emptyList())
    override val queue: StateFlow<List<Path>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    override val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.One)
    override val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    @Volatile
    private var player: VideoPlayer? = null
    private var pollJob: Job? = null

    /**
     * Where a level the transport set goes, so it survives the session.
     *
     * Handed over with the player rather than taken at construction, because the
     * wallpaper's loudness is a background setting and only the composable that
     * renders the background can write one. A hook that lived here permanently
     * would be mutable global state set from a composition, which is the shape
     * this avoids by living exactly as long as the attachment does.
     */
    @Volatile
    private var persistVolume: (Float) -> Unit = {}

    /**
     * Takes the player the background has just opened.
     *
     * Called again for the same wallpaper whenever a setting rebuilds it, which the
     * background does for the file, the sound and the decode policy. Replacing
     * rather than refusing is the whole contract: what arrives is always the live
     * one, and what it replaces is already being closed by its owner.
     */
    fun attach(
        player: VideoPlayer,
        file: Path,
        volume: Float,
        repeat: RepeatMode,
        persistVolume: (Float) -> Unit,
    ) {
        pollJob?.cancel()
        this.player = player
        this.persistVolume = persistVolume
        _volume.value = volume.coerceIn(0f, 1f)
        _repeat.value = repeat
        _queue.value = listOf(file)
        _queueIndex.value = 0
        // Cleared rather than kept: the tags belong to the file, and a rebuild for a
        // different wallpaper would otherwise show the previous one's name until the
        // read landed.
        _track.value = null
        _state.value = PlaybackState.Playing(file, positionMs = 0L, durationMs = 0L)
        pollJob = scope.launch { poll(player, file) }
    }

    /** Gives the player back. The flows keep their last values, which is what a paused wall looks like. */
    fun detach() {
        pollJob?.cancel()
        pollJob = null
        player = null
        persistVolume = {}
        _state.value = PlaybackState.Idle
        _track.value = null
        _queue.value = emptyList()
        _queueIndex.value = -1
    }

    /** What the level is now, when the setting moved rather than the transport. */
    fun reportVolume(level: Float) {
        _volume.value = level.coerceIn(0f, 1f)
    }

    /** What the loop is now, when the setting moved rather than the transport. */
    fun reportRepeat(mode: RepeatMode) {
        _repeat.value = mode
    }

    /**
     * The same rate the music player reads itself at, because the two are read by
     * one media session and a second cadence would make a jump mean different
     * things depending on which was playing.
     */
    private suspend fun poll(player: VideoPlayer, file: Path) {
        // This coroutine's own liveness, not the scope's. Cancelling the poll job
        // leaves the scope active, so asking the scope would make the loop rely on
        // delay throwing to stop, which is true today and a trap for whoever moves
        // the delay.
        while (coroutineContext.isActive) {
            val st = player.state
            _state.value = mapPlaybackState(
                file    = file,
                st      = st,
                // A wallpaper is playing from the moment it opens: nobody presses
                // play on it, so the distinction this flag draws for the music
                // player -- opened but never started -- has no wallpaper it fits.
                started = true,
                posMs   = player.positionNanos() / 1_000_000L,
                durMs   = (player.durationNanos ?: 0L) / 1_000_000L,
            )
            if (_track.value == null && st != VideoPlayer.State.Opening) readMetadata(player, file)
            if (st is VideoPlayer.State.Failed) {
                log.warn("Wallpaper playback failed for {}", file, st.cause)
                return
            }
            delay(AudioPlayer.POLL_INTERVAL_MS.milliseconds)
        }
    }

    /** Once per file, as soon as the player is past Opening, for the reason the music player gives. */
    private fun readMetadata(player: VideoPlayer, file: Path) {
        val artwork = player.coverArt?.let { runCatching { decodeArtwork(it) }.getOrNull() }
        _track.value = trackInfoFrom(player.tags, file, artwork)
    }

    // ── Transport ────────────────────────────────────────────────────────

    override fun play() {
        player?.resume()
    }

    override fun pause() {
        player?.pause()
    }

    /**
     * Back to the start, not playing. A wallpaper has nothing to release the way a
     * finished track does, so what stop can honestly mean here is the first frame
     * and a stopped clock.
     */
    override fun stop() {
        val p = player ?: return
        // Inexact, because the keyframe at or before zero IS zero, so it lands in
        // the same place having done none of the decode-forward run an exact seek
        // pays for. The scrub below keeps exact, which is what skinema's own note
        // says a timeline somebody is dragging wants.
        p.seek(0L, exact = false)
        p.pause()
    }

    override fun seek(positionMs: Long) {
        val p = player ?: return
        val wanted = positionMs.coerceAtLeast(0L) * 1_000_000L
        val duration = p.durationNanos
        p.seek(if (duration != null && duration > 0L) wanted.coerceAtMost(duration) else wanted)
    }

    override fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _volume.value = clamped
        player?.setVolume(clamped)
        persistVolume(clamped)
    }

    /**
     * Turns the lap or lets it end, which is the wallpaper's own loop under another
     * name. [RepeatMode.Queue] has no queue to go round, so it reads as [RepeatMode.One]
     * the way it already does for a music queue of one.
     */
    override fun setRepeat(mode: RepeatMode) {
        _repeat.value = mode
        player?.loop = mode != RepeatMode.Off
    }

    // ── The queue, which is one entry ────────────────────────────────────
    //
    // Inert rather than absent. A widget offers these unconditionally and gets
    // controls that do nothing at an edge, which is what its own skip buttons
    // already do at the end of a music queue that does not wrap. Making them throw,
    // or hiding them, would mean every player widget learning which kind of thing
    // it is pointed at, and the whole reason the contract is one interface is that
    // it does not have to.
    //
    // What a wallpaper IS playing is the background's to decide, and the way to
    // change it is to pick another one.

    override fun open(files: List<Path>) = Unit

    override fun enqueue(files: List<Path>) = Unit

    override fun playAt(index: Int) = Unit

    override fun skipToNext() = Unit

    override fun skipToPrevious() = Unit

    override fun removeAt(index: Int) = Unit
}
