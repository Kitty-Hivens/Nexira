package hivens.ui.widgets.services

import hivens.ui.audio.AudioPlayer

// Adapter from the cross-widget service contract to the concrete
// javax.sound.sampled-backed AudioPlayer. AudioPlayer is the Koin
// singleton -- one player per launcher process -- so every widget
// that mounts MusicPlayerWidget binds to the same underlying state.
// Removing the widget unregisters the service but leaves AudioPlayer
// alive; re-adding the widget re-binds to the same player and the
// track keeps playing.
//
// When Skinema lands and AudioPlayer is replaced by a Panama-FFmpeg
// engine, this file is the single seam: the MusicPlayerService
// interface stays, Impl points at the new engine, every consumer
// keeps working.
class MusicPlayerServiceImpl(
    private val player: AudioPlayer,
) : MusicPlayerService {
    override val state get() = player.state
    override val volume get() = player.volume

    override fun setVolume(level: Float) = player.setVolume(level)
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
}
