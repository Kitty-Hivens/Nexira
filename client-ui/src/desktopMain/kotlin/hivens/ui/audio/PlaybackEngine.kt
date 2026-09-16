package hivens.ui.audio

import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path

/**
 * One open file, as the player above it needs to see one.
 *
 * Ten members, and every one of them is a call [AudioPlayer] already made on
 * skinema's own player. Nothing is added, renamed or reordered: this is that
 * surface written down, so the orchestration around it can be driven without
 * FFmpeg on the path.
 *
 * The orchestration is where the behaviour lives. What a queue does at its end,
 * what a repeat mode means, whether a scrub into a finished track reopens it and
 * in what state, whether a track running into the next one is ever reported as
 * paused: all of that is decisions made above this interface, and all of it used
 * to be reachable only by loading a real file through real natives. The pure
 * helpers beside it were extracted for the same reason and caught real defects,
 * but they only answer questions that have no engine in them.
 *
 * [VideoPlayer.State] stays as skinema's own type rather than being mirrored. It
 * is a sealed hierarchy of plain objects in a pure module, the mapping onto the
 * launcher's states is already tested against it, and a parallel enum here would
 * be a second thing to keep in step for no gain.
 */
public interface PlaybackEngine : AutoCloseable {

    /** Where the decode has got to, as skinema reports it. */
    public val state: VideoPlayer.State

    /** The container's length, or null until it has said. */
    public val durationNanos: Long?

    /** The file's tags, empty for a container that carries none. */
    public val tags: Map<String, String>

    /** The embedded cover, still encoded, or null. */
    public val coverArt: ByteArray?

    /** Where the audio device actually is, in nanoseconds. */
    public fun positionNanos(): Long

    /** Jump. Nanoseconds, because that is what the engine counts in. */
    public fun seek(nanos: Long)

    /** Resume after a pause. */
    public fun resume()

    /** Hold where it stands. */
    public fun pause()

    /** Linear 0..1. */
    public fun setVolume(level: Float)

    /** Release the decode thread and the device. Blocks while it joins. */
    override fun close()
}

/**
 * Opens a file, or throws the way the real one throws.
 *
 * A function rather than a class because that is all it is, and because the
 * failure modes are part of the contract: an ordinary exception for a file that
 * will not open, and a [LinkageError] for a natives bundle that will not load.
 * The caller catches both and closes the sink it was handed, so a stand-in that
 * wants to exercise either path simply throws it.
 */
public fun interface PlaybackEngines {
    public fun open(file: Path, sink: PcmSink?): PlaybackEngine
}

/**
 * The real one: skinema's player, with nothing in front of it.
 *
 * `loop = false` is load bearing and is not this class's to change. The queue
 * above decides what happens at the end of a file, and an engine that turns the
 * lap by itself would take that decision away and make a repeat mode a lie.
 */
public object SkinemaEngines : PlaybackEngines {
    override fun open(file: Path, sink: PcmSink?): PlaybackEngine =
        SkinemaEngine(VideoPlayer(path = file, loop = false, audio = true, sink = sink))
}

private class SkinemaEngine(private val player: VideoPlayer) : PlaybackEngine {
    override val state: VideoPlayer.State get() = player.state
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
