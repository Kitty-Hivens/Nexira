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

    /** What the position read on the previous poll, for spotting a jump. */
    private var lastPositionMs = 0L

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
            log.info("Media session published as {}", IDENTITY)

            // Which file the cover on the wire belongs to, and whether it has been
            // written from that file's own tags yet.
            var artFor: Path? = null
            var artRead = false
            var artUrl: String? = null
            while (isActive) {
                val state = player.state.value
                val track = player.track.value
                val file = state.file
                if (file != artFor) {
                    // A new file drops the previous cover at once. The player clears
                    // its metadata on load and fills it again a moment later from the
                    // decode thread, so keying this on the track instead left the old
                    // picture standing through that gap: the new title beside the
                    // previous track's art, which is the partial update SessionState
                    // is explicit about.
                    artFor = file
                    artRead = false
                    artUrl = null
                }
                if (file != null && !artRead && track != null) {
                    artRead = true
                    artUrl = withContext(Dispatchers.IO) { writeArt(track.artwork) }
                }
                publish(state, track, artUrl)
                delay(POLL_MS.milliseconds)
            }
        }
    }

    /**
     * Hands the whole state over on every poll, and announces the position
     * separately when it jumped.
     *
     * Every poll, with no comparison of our own, and that is the load-bearing
     * part. MPRIS leaves the position out of its property-change notifications
     * deliberately, because a number that moves continuously would be a bus
     * message per tick and a redraw in every widget listening; what a reader
     * does instead is poll the property, and what that property answers is the
     * last state published here. So withholding a publish because only the
     * position moved is not an optimisation, it is the scrubber freezing in
     * every widget on the bus while the audio plays on.
     *
     * Cheap, because the comparison already exists one layer down: the session
     * diffs the state against what it last announced and emits nothing where
     * nothing changed. Doing it here as well duplicated that work and got the
     * one property it excludes wrong.
     *
     * The jump is the other half, and it cannot be inferred. A position that
     * advanced and a position that was seeked look identical to anything
     * comparing two snapshots, and a reader extrapolating between polls has no
     * way to tell them apart, so the player has to say. A track change is one of
     * these: the position drops to zero without anybody having seeked.
     */
    private fun publish(state: PlaybackState, track: TrackInfo?, artUrl: String?) {
        val s = session ?: return
        val positionMs = positionOf(state)
        val next = SessionState(
            playback = when (state) {
                is PlaybackState.Playing -> SessionPlayback.PLAYING
                is PlaybackState.Paused -> SessionPlayback.PAUSED
                is PlaybackState.Ready -> SessionPlayback.PAUSED
                is PlaybackState.Idle, is PlaybackState.Error -> SessionPlayback.STOPPED
            },
            metadata = metadataOf(state, track, artUrl),
            positionMicros = positionMs * 1_000L,
            // The player's own loudness, which is a separate slider from the
            // stream's in the system mixer. Left at its default this published a
            // constant full volume, so a widget's slider sat at the top whatever
            // the player was set to, and a drag on it snapped straight back.
            volume = player.volume.value.toDouble(),
            canPlay = state !is PlaybackState.Idle,
            // Whether this player can be paused at all, not whether it is playing
            // right now. The distinction is the protocol's: these properties
            // describe what the player is able to do, and tying one to the
            // transport made it flip on every press.
            canPause = state !is PlaybackState.Idle,
            canGoNext = player.queue.value.size > 1,
            canGoPrevious = player.queue.value.size > 1,
            canSeek = durationOf(state) > 0L,
            loop = when (player.repeat.value) {
                RepeatMode.Off -> LoopMode.NONE
                RepeatMode.One -> LoopMode.TRACK
                RepeatMode.Queue -> LoopMode.PLAYLIST
            },
        )

        runCatching { s.publish(next) }.onFailure { log.warn("Could not publish the session state", it) }
        if (jumped(positionMs)) {
            runCatching { s.seeked(positionMs * 1_000L) }
                .onFailure { log.warn("Could not announce a seek", it) }
        }
        lastPositionMs = positionMs
    }

    /**
     * Whether the position moved by more than one poll's worth of playing.
     *
     * Backwards at all, or forwards by more than the interval plus a margin. The
     * margin is what keeps an ordinary tick from being read as a seek: a poll
     * that ran late, or a clock that advanced across a track boundary, moves the
     * number by more than the nominal interval without anybody having sought.
     */
    private fun jumped(positionMs: Long): Boolean {
        val delta = positionMs - lastPositionMs
        return delta < 0L || delta > POLL_MS + JUMP_MARGIN_MS
    }

    private fun metadataOf(state: PlaybackState, track: TrackInfo?, artUrl: String?): TrackMetadata {
        if (state is PlaybackState.Idle) return TrackMetadata.EMPTY
        val duration = durationOf(state)
        return TrackMetadata(
            title = track?.title ?: state.file?.fileName?.toString(),
            artists = listOfNotNull(track?.artist),
            album = track?.album,
            durationMicros = duration.takeIf { it > 0L }?.times(1_000L),
            artUrl = artUrl,
            trackId = trackIdOf(state.file),
        )
    }

    /**
     * What names the loaded track on the wire.
     *
     * The path identifies it, and a queue can hold the same file twice, so the
     * index goes with it. One function rather than two literals because the id is
     * minted here and compared here: a desktop sends it back with a seek to say
     * which track it believed was playing, and an identity that is built one way
     * and checked another would fail every comparison.
     */
    private fun trackIdOf(file: Path?): String? = file?.let { "${player.queueIndex.value}:$it" }

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
                // The track the sender believed was playing, which is the whole
                // reason the command carries one. A widget still showing the
                // previous track when somebody clicked its scrubber would
                // otherwise seek whatever replaced it, and a track change is
                // exactly when a click is most likely to be one poll behind.
                //
                // No id is not a mismatch. A reader that sends none is making no
                // claim about which track this is, and refusing it would drop
                // every seek from a widget that does not track identities.
                val current = trackIdOf(player.state.value.file)
                if (command.trackId == null || command.trackId == current) {
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

        /**
         * How often the player is read, which is also how stale the position a
         * desktop reads can be.
         *
         * Slower than the transport's own five a second, because nothing here
         * draws and a bus message only leaves when something other than the
         * position changed. Half a second is under what a reader's own redraw
         * would resolve, and it is the anchor such a reader extrapolates from
         * between its polls rather than the rate it redraws at.
         */
        const val POLL_MS = 500L

        const val JUMP_MARGIN_MS = 750L
    }
}

private fun positionOf(state: PlaybackState): Long = when (state) {
    is PlaybackState.Playing -> state.positionMs
    is PlaybackState.Paused -> state.positionMs
    is PlaybackState.Ready -> state.positionMs
    is PlaybackState.Idle, is PlaybackState.Error -> 0L
}

private fun durationOf(state: PlaybackState): Long = when (state) {
    is PlaybackState.Playing -> state.durationMs
    is PlaybackState.Paused -> state.durationMs
    is PlaybackState.Ready -> state.durationMs
    is PlaybackState.Idle, is PlaybackState.Error -> 0L
}
