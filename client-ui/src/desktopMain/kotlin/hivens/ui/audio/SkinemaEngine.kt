package hivens.ui.audio

import dev.hivens.libsound.LatencyProfile
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.player.VideoPlayer
import hivens.ui.diag.SkinemaGate
import java.nio.file.Path

/**
 * The real one: skinema's player, with nothing in front of it.
 *
 * `loop = false` is load bearing and is not this class's to change. The queue
 * above decides what happens at the end of a file, and an engine that turns the
 * lap by itself would take that decision away and make a repeat mode a lie.
 *
 * [output] is where the sound leaves, when the system will have it under our own
 * name. Null, and skinema opens a line for itself exactly as it always did. That is
 * the fallback and not a degraded mode: what the system output buys is a stream the
 * mixer shows as Nexira rather than as an anonymous JVM, and losing that must never
 * cost the sound itself.
 *
 * [construct] builds the engine from a file and the stream, and is a parameter so
 * the handling of that stream can be driven without FFmpeg on the path.
 */
public class SkinemaEngines(
    private val output: AudioOutput? = null,
    private val construct: (Path, PcmSink?) -> PlaybackEngine = ::openSkinema,
) : PlaybackEngines {

    override fun open(file: Path): PlaybackEngine {
        if (!SkinemaGate.enabled) throw EngineUnavailable("the skinema module is disabled")
        // Named rather than passed inline, so an engine that does not take it
        // leaves it to somebody. Skinema closes the sink it was handed and can
        // only do that once it has one, and the one thing that reliably throws
        // out of this constructor is a natives bundle that will not load, which
        // does not get better on the next press: each press opened a stream on
        // the sound server and dropped it unreferenced. Not yet a stream on the
        // graph, since a sink connects on its first open, but it is an arena and
        // a registration and it is ours until the engine takes it.
        //
        // RELAXED (a 200 ms buffer) because this is a music track nobody is syncing
        // to a picture: the depth rides out an occasional GC pause a shorter buffer
        // would underrun on, and the added latency is imperceptible for playback.
        // The video wallpaper keeps the default, where audio must stay in step with
        // the frame.
        val sink = output?.sink(LatencyProfile.RELAXED)
        return try {
            construct(file, sink)
        } catch (e: Exception) {
            val _ = runCatching { sink?.close() }
            throw e
        } catch (e: LinkageError) {
            val _ = runCatching { sink?.close() }
            throw e
        }
    }
}

private fun openSkinema(file: Path, sink: PcmSink?): PlaybackEngine =
    SkinemaEngine(VideoPlayer(input = MediaInput.File(file), loop = false, audio = true, sink = sink))

private class SkinemaEngine(private val player: VideoPlayer) : PlaybackEngine {
    override val state: EngineState get() = player.state.toEngineState()
    override val durationNanos: Long? get() = player.durationNanos
    override val tags: Map<String, String> get() = player.tags
    override val coverArt: ByteArray? get() = player.coverArt
    override fun positionNanos(): Long = player.positionNanos()
    override fun seek(nanos: Long) = player.seek(nanos)
    override fun resume() = player.resume()
    override fun pause() = player.pause()
    override fun setVolume(level: Float) = player.setVolume(level)
    override fun close() = player.close()
}

/** Skinema's state in the launcher's words. Exhaustive on purpose, see [EngineState]. */
internal fun VideoPlayer.State.toEngineState(): EngineState = when (this) {
    VideoPlayer.State.Opening -> EngineState.Opening
    VideoPlayer.State.Seeking -> EngineState.Seeking
    VideoPlayer.State.Playing -> EngineState.Playing
    VideoPlayer.State.Paused -> EngineState.Paused
    VideoPlayer.State.Ended -> EngineState.Ended
    is VideoPlayer.State.Failed -> EngineState.Failed(cause)
    VideoPlayer.State.Closed -> EngineState.Closed
}
