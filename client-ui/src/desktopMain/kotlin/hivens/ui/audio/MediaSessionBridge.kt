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

    /** The last state published, so the next one is a copy with what moved changed. */
    private var published: SessionState? = null

    /** What the position read when it was last published, for spotting a jump. */
    private var lastPositionMs = 0L

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

            var artFor: String? = null
            var artUrl: String? = null
            while (isActive) {
                val state = player.state.value
                val track = player.track.value
                val key = track?.let { "${state.file}|${it.title}" }
                if (key != null && key != artFor) {
                    artFor = key
                    artUrl = withContext(Dispatchers.IO) { writeArt(track.artwork) }
                }
                publish(state, track, artUrl)
                delay(POLL_MS.milliseconds)
            }
        }
    }

    /**
     * Sends the state on when something other than the position moved, and
     * announces the position on its own when it jumped.
     *
     * Both halves matter. A whole state five times a second is a D-Bus message
     * five times a second for a number the desktop can read whenever it likes,
     * and MPRIS has a property for exactly that reason. A seek nobody asked us
     * for is the one case the desktop cannot work out for itself, because its
     * widget is extrapolating from the last position it was told.
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
            canPlay = state !is PlaybackState.Idle,
            canPause = state is PlaybackState.Playing,
            canGoNext = player.queue.value.size > 1,
            canGoPrevious = player.queue.value.size > 1,
            canSeek = durationOf(state) > 0L,
            loop = when (player.repeat.value) {
                RepeatMode.Off -> LoopMode.NONE
                RepeatMode.One -> LoopMode.TRACK
                RepeatMode.Queue -> LoopMode.PLAYLIST
            },
        )

        val previous = published
        // Compared without the position, which moves on its own and is not news.
        if (previous == null || previous.copy(positionMicros = 0) != next.copy(positionMicros = 0)) {
            runCatching { s.publish(next) }.onFailure { log.warn("Could not publish the session state", it) }
            published = next
            lastPositionMs = positionMs
            return
        }
        if (jumped(positionMs)) {
            runCatching { s.seeked(positionMs * 1_000L) }
                .onFailure { log.warn("Could not announce a seek", it) }
            published = next
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
            // The path is what identifies a track here, and a queue can hold the
            // same file twice, so the index goes with it. A desktop compares this
            // against what it believed was playing before acting on a seek.
            trackId = state.file?.let { "${player.queueIndex.value}:$it" },
        )
    }

    /**
     * Writes [artwork] where the desktop can read it, and answers its URL.
     *
     * A file rather than the bytes themselves, because MPRIS carries a URL and
     * not a picture. One file, overwritten per track: a cache keyed by track
     * would grow without a bound for a gain nobody can see, since the only reader
     * is a widget showing the one thing playing now.
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
            val file = artDir.resolve(ART_FILE)
            val bytes = Image.makeFromBitmap(artwork.asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG)
                ?.bytes
                ?: return null
            Files.write(file, bytes)
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
            is SessionCommand.SetPosition -> player.seek(command.positionMicros / 1_000L)
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
        published = null
    }

    private companion object {
        const val APPLICATION = "Nexira"
        const val IDENTITY = "Nexira"
        const val DESKTOP_ENTRY = "nexira"
        const val ART_FILE = "now-playing.png"

        /**
         * How often the player is read. Slower than the transport's own five a
         * second, because nothing here draws: what this produces is a bus message
         * when something changed, and the position the desktop reads on demand.
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
