package dev.hivens.nexira

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import hivens.ui.audio.EngineState
import hivens.ui.audio.PlaybackEngine
import hivens.ui.audio.PlaybackEngines
import java.nio.file.Path

/**
 * ExoPlayer behind the player's engine seam.
 *
 * Every call into a Media3 player has to come from the thread it was built on, so
 * [looper] is that thread's, and [open] refuses to run anywhere else. The player
 * above confines all of its engine work to one dispatcher, and on Android that
 * dispatcher is handed the same looper, which is what makes the two rules agree
 * instead of being enforced by luck.
 */
class Media3Engines(
    private val context: Context,
    private val looper: Looper,
) : PlaybackEngines {

    override fun open(file: Path): PlaybackEngine {
        check(Looper.myLooper() === looper) { "a Media3 player is opened on its own thread" }
        val player = ExoPlayer.Builder(context)
            .setLooper(looper)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val engine = Media3Engine(player)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file.toFile())))
        // Playing from the first moment, the way the desktop engine does: the player
        // above silences and pauses an open nobody asked to hear, in that order.
        player.playWhenReady = true
        player.prepare()
        return engine
    }
}

private class Media3Engine(private val player: ExoPlayer) : PlaybackEngine, Player.Listener {

    private var failure: Throwable? = null
    private var released = false
    private var everReady = false

    init {
        player.addListener(this)
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e("NexiraProbe", "ExoPlayer error ${error.errorCodeName}", error)
        failure = error
    }

    override val state: EngineState
        get() {
            if (released) return EngineState.Closed
            failure?.let { return EngineState.Failed(it) }
            return when (player.playbackState) {
                Player.STATE_IDLE -> EngineState.Opening
                Player.STATE_BUFFERING -> if (everReady) EngineState.Seeking else EngineState.Opening
                Player.STATE_READY -> {
                    everReady = true
                    if (player.playWhenReady) EngineState.Playing else EngineState.Paused
                }
                Player.STATE_ENDED -> EngineState.Ended
                else -> EngineState.Opening
            }
        }

    override val durationNanos: Long?
        get() = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }?.times(NANOS_PER_MILLI)

    override val tags: Map<String, String>
        get() {
            val meta = player.mediaMetadata
            return buildMap {
                meta.title?.let { put("title", it.toString()) }
                meta.artist?.let { put("artist", it.toString()) }
                meta.albumArtist?.let { put("album_artist", it.toString()) }
                meta.albumTitle?.let { put("album", it.toString()) }
            }
        }

    override val coverArt: ByteArray? get() = player.mediaMetadata.artworkData

    override fun positionNanos(): Long = player.currentPosition * NANOS_PER_MILLI

    override fun seek(nanos: Long) = player.seekTo(nanos / NANOS_PER_MILLI)

    override fun resume() = player.play()

    override fun pause() = player.pause()

    override fun setVolume(level: Float) {
        player.volume = level
    }

    override fun close() {
        released = true
        player.removeListener(this)
        player.release()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
