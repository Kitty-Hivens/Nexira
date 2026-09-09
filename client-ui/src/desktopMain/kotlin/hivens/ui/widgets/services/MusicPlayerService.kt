package hivens.ui.widgets.services

import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.widget.model.WidgetService
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

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

    /**
     * The loaded track's own name and picture, null when nothing is loaded. A
     * consumer that renders "what is playing" needs this rather than the file
     * path on [state], and it changes once a track instead of five times a
     * second.
     */
    val track: StateFlow<TrackInfo?>

    /**
     * Jump to [positionMs]. Milliseconds, matching what [state] reports, so a
     * consumer that drew a measure from a position can hand the same number back.
     */
    fun seek(positionMs: Long)

    fun setVolume(level: Float)
    fun play()
    fun pause()
    fun stop()

    /**
     * What is loaded and what follows it, and which entry of it is loaded (-1 for
     * none). Paths rather than tracks: reading tags for a queue of forty entries
     * would mean opening forty engines, so a consumer drawing the queue draws file
     * names and only [track] carries the real metadata of the entry being played.
     */
    val queue: StateFlow<List<Path>>
    val queueIndex: StateFlow<Int>

    /**
     * What happens at the END of the queue. Between its entries a queue always
     * steps; the mode decides whether the end wraps, stops, or never arrives.
     */
    val repeat: StateFlow<RepeatMode>
    fun setRepeat(mode: RepeatMode)

    /** Replaces the queue and loads its first entry, silent. What a picker does. */
    fun open(files: List<Path>)

    /** Appends to the queue without disturbing what is playing. */
    fun enqueue(files: List<Path>)

    /** Loads one entry and plays it. For a click on a queue row. */
    fun playAt(index: Int)

    /**
     * Steps the queue, keeping whether it was sounding. Does nothing at an end the
     * mode does not wrap, so a consumer can offer them unconditionally and get a
     * control that is simply inert at the edge.
     */
    fun skipToNext()
    fun skipToPrevious()

    /** Drops one entry, moving the engine only when the dropped entry was loaded. */
    fun removeAt(index: Int)
}
