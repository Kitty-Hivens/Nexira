package dev.hivens.nexira

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.fileTitle
import hivens.ui.widgets.services.MusicPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * The launcher's player, as the system's media session sees a player.
 *
 * The queue, the repeat mode and the transport state live in [MusicPlayerService], not
 * in any ExoPlayer, so the session cannot simply wrap one. This reads the service's
 * flows into the state the session wants and turns the session's commands back into
 * calls on the service, which is the same job the MPRIS bridge does on the desktop.
 */
class MusicPlayerPlayer(
    private val service: MusicPlayerService,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var cover: Pair<ImageBitmap, ByteArray>? = null

    private val watching: Job = scope.launch(Dispatchers.Main) {
        combine(
            combine(service.state, service.track, service.volume) { a, b, c -> Triple(a, b, c) },
            combine(service.queue, service.queueIndex, service.repeat) { a, b, c -> Triple(a, b, c) },
        ) { a, b -> a to b }.collect { invalidateState() }
    }

    override fun getState(): State {
        val playback = service.state.value
        val track = service.track.value
        val queue = service.queue.value
        val index = service.queueIndex.value
        val playing = playback is PlaybackState.Playing
        val duration = when (playback) {
            is PlaybackState.Ready -> playback.durationMs
            is PlaybackState.Playing -> playback.durationMs
            is PlaybackState.Paused -> playback.durationMs
            else -> 0L
        }
        val position = when (playback) {
            is PlaybackState.Ready -> playback.positionMs
            is PlaybackState.Playing -> playback.positionMs
            is PlaybackState.Paused -> playback.positionMs
            else -> 0L
        }

        val items = queue.mapIndexed { i, path ->
            val current = i == index
            val metadata = MediaMetadata.Builder()
                .setTitle(if (current) track?.title ?: fileTitle(path) else fileTitle(path))
                .apply {
                    if (current) {
                        track?.artist?.let { setArtist(it) }
                        track?.album?.let { setAlbumTitle(it) }
                        track?.artwork?.let { art -> setArtworkData(encoded(art), MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    }
                }
                .build()
            MediaItemData.Builder(path.toString())
                .setMediaItem(MediaItem.Builder().setMediaId(path.toString()).setMediaMetadata(metadata).build())
                .setDurationUs(if (current && duration > 0L) duration * 1_000L else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }

        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index.coerceAtLeast(0))
            .setPlaybackState(if (playback is PlaybackState.Idle || playback is PlaybackState.Error) STATE_IDLE else STATE_READY)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(position)
            .setRepeatMode(
                when (service.repeat.value) {
                    RepeatMode.Off -> REPEAT_MODE_OFF
                    RepeatMode.One -> REPEAT_MODE_ONE
                    RepeatMode.Queue -> REPEAT_MODE_ALL
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) service.play() else service.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        service.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> service.skipToNext()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> service.skipToPrevious()
            else -> {
                if (mediaItemIndex != service.queueIndex.value) service.playAt(mediaItemIndex)
                else service.seek(if (positionMs == C.TIME_UNSET) 0L else positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        service.setRepeat(
            when (repeatMode) {
                REPEAT_MODE_ONE -> RepeatMode.One
                REPEAT_MODE_ALL -> RepeatMode.Queue
                else -> RepeatMode.Off
            },
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        watching.cancel()
        return Futures.immediateVoidFuture()
    }

    /** The picture as the session wants it, encoded once per picture rather than per state. */
    private fun encoded(art: ImageBitmap): ByteArray {
        cover?.let { (known, bytes) -> if (known === art) return bytes }
        val out = ByteArrayOutputStream()
        art.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray().also { cover = art to it }
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
            )
            .build()
    }
}
