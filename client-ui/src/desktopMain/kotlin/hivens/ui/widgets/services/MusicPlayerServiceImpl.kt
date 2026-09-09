package hivens.ui.widgets.services

import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.RepeatMode
import java.nio.file.Path

// Adapter from the cross-widget service contract to the concrete
// Skinema-backed AudioPlayer (FFmpeg via Panama). AudioPlayer is the
// Koin singleton -- one player per launcher process -- so every widget
// that mounts MusicPlayerWidget binds to the same underlying state.
// Removing the widget unregisters the service but leaves AudioPlayer
// alive; re-adding the widget re-binds to the same player and the
// track keeps playing.
//
// The engine swap (javax.sound -> Skinema) happened inside AudioPlayer
// behind this seam: the MusicPlayerService interface and every consumer
// kept working unchanged.
class MusicPlayerServiceImpl(
    private val player: AudioPlayer,
) : MusicPlayerService {
    override val state get() = player.state
    override val volume get() = player.volume
    override val track get() = player.track

    override val queue get() = player.queue
    override val queueIndex get() = player.queueIndex
    override val repeat get() = player.repeat

    override fun seek(positionMs: Long) = player.seek(positionMs)
    override fun setVolume(level: Float) = player.setVolume(level)
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun setRepeat(mode: RepeatMode) = player.setRepeat(mode)
    override fun open(files: List<Path>) = player.open(files)
    override fun enqueue(files: List<Path>) = player.enqueue(files)
    override fun playAt(index: Int) = player.playAt(index)
    override fun skipToNext() = player.skipToNext()
    override fun skipToPrevious() = player.skipToPrevious()
    override fun removeAt(index: Int) = player.removeAt(index)
}
