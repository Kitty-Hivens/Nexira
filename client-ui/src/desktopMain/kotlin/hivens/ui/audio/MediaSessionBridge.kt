package hivens.ui.audio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import dev.hivens.libsound.LoopMode
import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.PlaybackState as SessionPlayback
import dev.hivens.libsound.session.MediaSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Publishes what the launcher is playing to the desktop, and takes its media
 * keys back.
 *
 * MPRIS on Linux, and whatever the platform's equivalent is elsewhere. Nothing
 * here touches the audio path: a session is a conversation on the bus about
 * sound, not a claim on a device, so this can run beside skinema holding the
 * line without either knowing about the other.
 *
 * Absent is an ordinary answer. No session bus, no backend for this platform, or
 * another process already holding the name, and [MediaSessions.open] returns
 * null; the launcher plays exactly as before and the desktop simply shows
 * nothing. That is why every path here is null-tolerant rather than guarded by a
 * capability check at the call site.
 *
 * ## What is here and what is beside it
 *
 * This class owns the two things a test cannot have: a connection to the bus and
 * a timer. Everything it decides lives in [SessionPublisher], [CoverTrail] and
 * [sessionStateOf], which take plain data and can be driven through a whole run
 * of a track without either. That split is not tidiness. Five things this bridge
 * published were stale or wrong at once and every one of them was found by
 * reading rather than by using the launcher, because there was nothing that
 * could fail.
 */
class MediaSessionBridge(
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
    /** Where a cover is written so the desktop can read it back by URL. */
    private val artDir: Path,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(MediaSessionBridge::class.java)

    private var session: MediaSession? = null
    private var job: Job? = null

    /** Names each cover apart, so a new one is a new URL. */
    private var artSerial = 0

    fun start() {
        if (job != null) return
        job = scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    MediaSessions.open(
                        SessionConfig(
                            applicationName = APPLICATION,
                            identity = IDENTITY,
                            desktopEntry = DESKTOP_ENTRY,
                            // The launcher's window is its own to raise, and quitting
                            // it from a media widget is not something a person means
                            // when they press a key on a keyboard.
                            canRaise = false,
                            canQuit = false,
                        ),
                    )
                }.onFailure { log.warn("Media session unavailable", it) }.getOrNull()
            } ?: run {
                log.info("No media session on this desktop; playback is unaffected")
                return@launch
            }
            session = opened
            opened.onCommand { command -> scope.launch { handle(command) } }
            releaseOnShutdown()
            log.info("Media session published as {}", IDENTITY)

            val publisher = SessionPublisher(opened) { message, cause -> log.warn(message, cause) }
            val covers = CoverTrail()
            while (isActive) {
                val snapshot = snapshot()
                // Off the poll's own thread: a several-megapixel cover is a decode
                // and an encode, and the loop is what keeps the desktop's position
                // moving.
                val artUrl = covers.urlFor(snapshot) { artwork ->
                    withContext(Dispatchers.IO) { writeArt(artwork) }
                }
                publisher.publish(snapshot, artUrl)
                delay(SESSION_POLL_MS.milliseconds)
            }
        }
    }

    /** Everything the session is built from, read in one go so the parts cannot disagree. */
    private fun snapshot() = PlayerSnapshot(
        state = player.state.value,
        track = player.track.value,
        volume = player.volume.value,
        queueSize = player.queue.value.size,
        queueIndex = player.queueIndex.value,
        repeat = player.repeat.value,
    )

    /**
     * Writes [artwork] where the desktop can read it, and answers its URL.
     *
     * A file rather than the bytes themselves, because MPRIS carries a URL and
     * not a picture. A NEW name each time, which is the whole point: one file
     * overwritten in place keeps the same URL, and a widget that already loaded
     * that URL has no reason to read it again, so every track after the first
     * showed the first track's cover. The name is what tells the desktop the
     * picture changed.
     *
     * The one before last is deleted as the next is written, so the directory
     * holds two: the one being pointed at and the one a widget may still be
     * reading while it catches up.
     *
     * The picture is re-encoded from the decoded bitmap rather than kept from the
     * file, which costs a PNG of at most 512 points a side, once a track. Holding
     * the original bytes through the player to save that would put a megabyte of
     * cover in memory for the whole time a track is loaded.
     */
    private fun writeArt(artwork: ImageBitmap?): String? {
        if (artwork == null) return null
        return runCatching {
            Files.createDirectories(artDir)
            val bytes = Image.makeFromBitmap(artwork.asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG)
                ?.bytes
                ?: return null
            val file = artDir.resolve("cover-${artSerial++}.png")
            Files.write(file, bytes)
            Files.deleteIfExists(artDir.resolve("cover-${artSerial - 3}.png"))
            file.toUri().toString()
        }.onFailure { log.warn("Could not write the cover for the media session", it) }.getOrNull()
    }

    private suspend fun handle(command: SessionCommand) {
        when (command) {
            SessionCommand.Play -> player.play()
            SessionCommand.Pause -> player.pause()
            SessionCommand.PlayPause ->
                if (player.state.value is PlaybackState.Playing) player.pause() else player.play()
            SessionCommand.Stop -> player.stop()
            SessionCommand.Next -> player.skipToNext()
            SessionCommand.Previous -> player.skipToPrevious()
            is SessionCommand.Seek -> {
                val now = positionOf(player.state.value)
                player.seek(now + command.offsetMicros / 1_000L)
            }
            is SessionCommand.SetPosition -> {
                val current = trackIdOf(player.state.value.file, player.queueIndex.value)
                if (acceptsSeek(command.trackId, current)) {
                    player.seek(command.positionMicros / 1_000L)
                } else {
                    log.debug("Dropping a stale seek for {} while {} is loaded", command.trackId, current)
                }
            }
            is SessionCommand.SetLoop -> player.setRepeat(
                when (command.loop) {
                    LoopMode.NONE -> RepeatMode.Off
                    LoopMode.TRACK -> RepeatMode.One
                    LoopMode.PLAYLIST -> RepeatMode.Queue
                },
            )
            is SessionCommand.SetVolume -> player.setVolume(command.volume.toFloat())
            // Shuffle has no counterpart in the player yet, fullscreen has no
            // meaning for a card in a launcher, and the last two are the root's
            // own. Listed rather than swept into an else so a member added
            // upstream fails the build instead of going quiet.
            is SessionCommand.SetShuffle,
            is SessionCommand.SetFullscreen,
            SessionCommand.Raise,
            SessionCommand.Quit,
            -> Unit
        }
    }

    /**
     * Takes the session off the bus when the process goes, which nothing else
     * does.
     *
     * Registered once a session exists rather than at construction, so a desktop
     * that published nothing installs no hook. The session's own contract asks for
     * this in as many words: a player left published after it has stopped
     * answering is worse than no player. Exiting does release the bus name by
     * dropping the connection, so what this buys is the release happening because
     * the launcher said so rather than because its socket closed, which is the
     * difference between a quit and a crash from the desktop's side.
     *
     * The lambdas are built here and not at shutdown, the same reason the layout
     * flush gives: a lambda's class loads when its first instance is made, and a
     * hook that first touches its own generated classes while the process is
     * exiting cannot run at all if the image it was compiled from has been
     * replaced underneath it.
     */
    private fun releaseOnShutdown() {
        val body: () -> Unit = { close() }
        val task = Runnable { runCatching(body) }
        runCatching { Runtime.getRuntime().addShutdownHook(Thread(task, "nexira-media-session")) }
    }

    override fun close() {
        job?.cancel()
        job = null
        runCatching { session?.close() }
        session = null
    }

    private companion object {
        const val APPLICATION = "Nexira"
        const val IDENTITY = "Nexira"
        const val DESKTOP_ENTRY = "nexira"
    }
}

/**
 * How often the player is read, which is also how stale the position a desktop
 * reads can be.
 *
 * Slower than the transport's own five a second, because nothing here draws and a
 * bus message only leaves when something other than the position changed. Half a
 * second is under what a reader's own redraw would resolve, and it is the anchor
 * such a reader extrapolates from between its polls rather than the rate it
 * redraws at.
 */
internal const val SESSION_POLL_MS = 500L

/**
 * How far past one poll's worth of playing the position may move before it counts
 * as a jump.
 *
 * What keeps an ordinary tick from being read as a seek: a poll that ran late, or
 * a clock that advanced across a track boundary, moves the number by more than the
 * nominal interval without anybody having sought.
 */
internal const val SESSION_JUMP_MARGIN_MS = 750L

/**
 * Everything the session is built from, as of one poll.
 *
 * Read off the player in one go rather than field by field where each is needed,
 * so a track that changes half way through building a state cannot leave the new
 * title beside the old duration. Plain data, which is what lets the whole of the
 * publishing be driven through a run of a track without a player or a bus.
 */
internal data class PlayerSnapshot(
    val state: PlaybackState,
    val track: TrackInfo?,
    val volume: Float,
    val queueSize: Int,
    val queueIndex: Int,
    val repeat: RepeatMode,
) {
    val file: Path? get() = state.file
}

/**
 * What names the loaded track on the wire.
 *
 * The path identifies it, and a queue can hold the same file twice, so the index
 * goes with it. One function rather than two literals because the id is minted
 * here and compared here: a desktop sends it back with a seek to say which track
 * it believed was playing, and an identity built one way and checked another
 * would fail every comparison.
 */
internal fun trackIdOf(file: Path?, queueIndex: Int): String? = file?.let { "$queueIndex:$it" }

/**
 * Whether a seek naming [sent] should be acted on while [current] is loaded.
 *
 * The command carries the track its sender believed was playing, and that is the
 * whole reason it carries one: a widget still showing the previous track when
 * somebody clicked its scrubber would otherwise seek whatever replaced it, and a
 * track change is exactly when a click is most likely to be one poll behind.
 *
 * No id is not a mismatch. A reader that sends none is making no claim about which
 * track this is, and refusing it would drop every seek from a widget that does not
 * carry identities.
 */
internal fun acceptsSeek(sent: String?, current: String?): Boolean = sent == null || sent == current

/**
 * Whether the position moved further than playing could have moved it.
 *
 * Backwards at all, or forwards by more than one interval plus
 * [SESSION_JUMP_MARGIN_MS].
 *
 * A null [previousMs] is the first poll, and it is never a jump. There is nothing
 * for the position to be inconsistent with yet, and the state published alongside
 * carries it anyway, so announcing one would tell the desktop somebody sought when
 * the player had only just started answering. Treating the absent previous value
 * as a zero said exactly that for any first poll that was not at the very start of
 * a track.
 */
internal fun jumped(positionMs: Long, previousMs: Long?): Boolean {
    if (previousMs == null) return false
    val delta = positionMs - previousMs
    return delta < 0L || delta > SESSION_POLL_MS + SESSION_JUMP_MARGIN_MS
}

/**
 * The whole outward state, from one snapshot and whatever cover is on disk.
 *
 * Pure, and separated from the bridge for the reason the bridge's own
 * documentation gives: this is where five defects sat at once, and none of them
 * could have been caught by anything short of reading it.
 */
internal fun sessionStateOf(snapshot: PlayerSnapshot, artUrl: String?): SessionState {
    val state = snapshot.state
    return SessionState(
        playback = when (state) {
            is PlaybackState.Playing -> SessionPlayback.PLAYING
            is PlaybackState.Paused -> SessionPlayback.PAUSED
            is PlaybackState.Ready -> SessionPlayback.PAUSED
            is PlaybackState.Idle, is PlaybackState.Error -> SessionPlayback.STOPPED
        },
        metadata = metadataOf(snapshot, artUrl),
        positionMicros = positionOf(state) * 1_000L,
        // The player's own loudness, which is a separate slider from the stream's
        // in the system mixer. Left at its default this published a constant full
        // volume, so a widget's slider sat at the top whatever the player was set
        // to, and a drag on it snapped back.
        volume = snapshot.volume.toDouble(),
        canPlay = state !is PlaybackState.Idle,
        // Whether this player can be paused at all, not whether it is playing right
        // now. The distinction is the protocol's: these properties describe what
        // the player is able to do, and tying one to the transport made it flip on
        // every press.
        canPause = state !is PlaybackState.Idle,
        canGoNext = snapshot.queueSize > 1,
        canGoPrevious = snapshot.queueSize > 1,
        canSeek = durationOf(state) > 0L,
        loop = when (snapshot.repeat) {
            RepeatMode.Off -> LoopMode.NONE
            RepeatMode.One -> LoopMode.TRACK
            RepeatMode.Queue -> LoopMode.PLAYLIST
        },
    )
}

private fun metadataOf(snapshot: PlayerSnapshot, artUrl: String?): TrackMetadata {
    val state = snapshot.state
    if (state is PlaybackState.Idle) return TrackMetadata.EMPTY
    val duration = durationOf(state)
    return TrackMetadata(
        title = snapshot.track?.title ?: state.file?.fileName?.toString(),
        artists = listOfNotNull(snapshot.track?.artist),
        album = snapshot.track?.album,
        durationMicros = duration.takeIf { it > 0L }?.times(1_000L),
        artUrl = artUrl,
        trackId = trackIdOf(state.file, snapshot.queueIndex),
    )
}

/**
 * Turns a run of snapshots into what the session is told.
 *
 * ## Every poll, with no comparison of its own
 *
 * That is the load-bearing part and it reads backwards. MPRIS leaves the position
 * out of its property-change notifications deliberately, because a number that
 * moves continuously would be a bus message per tick and a redraw in every widget
 * listening; what a reader does instead is poll the property, and what that
 * property answers is the last state the player published. So withholding a
 * publish because only the position moved is not an optimisation, it is the
 * scrubber freezing in every widget on the bus while the audio plays on.
 *
 * It is cheap because the comparison already exists one layer down: the session
 * diffs the state against what it last announced and emits nothing where nothing
 * changed. Doing it here as well duplicated that work and got the one property it
 * excludes wrong.
 *
 * ## The jump is the other half
 *
 * A position that advanced and a position that was seeked look identical to
 * anything comparing two snapshots, and a reader extrapolating between polls has
 * no way to tell them apart, so the player has to say. A track change is one of
 * these: the position drops to zero without anybody having sought.
 */
internal class SessionPublisher(
    private val session: MediaSession,
    /** Where a refusal from the bus goes. Separate so a test can hold one without a logger. */
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {

    /**
     * What the position read on the previous turn, or null before there has been
     * one. Null rather than zero: a zero is a real position and would make the
     * first poll of a track already under way look like a seek.
     */
    private var lastPositionMs: Long? = null

    fun publish(snapshot: PlayerSnapshot, artUrl: String?) {
        runCatching { session.publish(sessionStateOf(snapshot, artUrl)) }
            .onFailure { onFailure("Could not publish the session state", it) }
        val positionMs = positionOf(snapshot.state)
        if (jumped(positionMs, lastPositionMs)) {
            runCatching { session.seeked(positionMs * 1_000L) }
                .onFailure { onFailure("Could not announce a seek", it) }
        }
        lastPositionMs = positionMs
    }
}

/**
 * Which cover URL belongs on the wire, across a run of polls.
 *
 * A track change arrives in two steps rather than one: the player clears its
 * metadata when it loads a file and fills it again from the decode thread a moment
 * later. Keyed on the track, that gap left the previous picture standing beside
 * the new title, which is the partial update the session state is explicit about.
 * So the file is what resets it and the metadata is what fills it.
 */
internal class CoverTrail {

    /** The file the current URL belongs to, or null when nothing is loaded. */
    private var forFile: Path? = null

    /** Whether the file's own tags have been looked at, which happens once per file. */
    private var read = false

    private var url: String? = null

    /**
     * [write] is called at most once per file, and only once that file's metadata
     * has arrived. A file whose tags carry no picture answers null and is not
     * asked again.
     */
    suspend fun urlFor(snapshot: PlayerSnapshot, write: suspend (ImageBitmap?) -> String?): String? {
        val file = snapshot.file
        if (file != forFile) {
            forFile = file
            read = false
            url = null
        }
        if (file != null && !read && snapshot.track != null) {
            read = true
            url = write(snapshot.track.artwork)
        }
        return url
    }
}

internal fun positionOf(state: PlaybackState): Long = when (state) {
    is PlaybackState.Playing -> state.positionMs
    is PlaybackState.Paused -> state.positionMs
    is PlaybackState.Ready -> state.positionMs
    is PlaybackState.Idle, is PlaybackState.Error -> 0L
}

internal fun durationOf(state: PlaybackState): Long = when (state) {
    is PlaybackState.Playing -> state.durationMs
    is PlaybackState.Paused -> state.durationMs
    is PlaybackState.Ready -> state.durationMs
    is PlaybackState.Idle, is PlaybackState.Error -> 0L
}
