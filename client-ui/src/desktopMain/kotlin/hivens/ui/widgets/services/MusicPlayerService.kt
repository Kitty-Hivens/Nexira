package hivens.ui.widgets.services

import hivens.ui.audio.PlaybackState
import hivens.widget.model.WidgetService
import kotlinx.coroutines.flow.StateFlow

// Cross-widget contract for any widget that wants to read or drive
// audio playback. Phase D's first concrete service. Backed by
// AudioPlayer (Skinema / FFmpeg via Panama); the engine swap from
// javax.sound stayed behind this interface, so consumers
// (PlaybackMiniControlWidget today, achievement watchers tomorrow,
// music-ducking-on-launch later) write against the contract once and
// never touch the engine.
//
// Reactive fields are StateFlow so non-Compose consumers (a
// background coroutine inside a future plugin, telemetry sink) can
// observe state without going through the registry's snapshot
// subscription. Compose consumers convert via .collectAsState() as
// they already do for AudioPlayer directly.
interface MusicPlayerService : WidgetService {
    val state: StateFlow<PlaybackState>
    val volume: StateFlow<Float>

    fun setVolume(level: Float)
    fun play()
    fun pause()
    fun stop()
}
