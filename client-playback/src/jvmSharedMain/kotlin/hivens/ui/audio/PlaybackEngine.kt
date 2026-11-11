package hivens.ui.audio

import java.nio.file.Path

/**
 * One open file, as the player above it needs to see one.
 *
 * Ten members, and every one of them is a call [AudioPlayer] already made on the
 * engine under it. Nothing is added, renamed or reordered: this is that surface
 * written down, so the orchestration around it can be driven without FFmpeg on the
 * path, and so a platform with another decoder can sit behind it.
 *
 * The orchestration is where the behaviour lives. What a queue does at its end,
 * what a repeat mode means, whether a scrub into a finished track reopens it and
 * in what state, whether a track running into the next one is ever reported as
 * paused: all of that is decisions made above this interface, and all of it used
 * to be reachable only by loading a real file through real natives. The pure
 * helpers beside it were extracted for the same reason and caught real defects,
 * but they only answer questions that have no engine in them.
 */
public interface PlaybackEngine : AutoCloseable {

    /** Where the decode has got to. */
    public val state: EngineState

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
 * Where an engine's decode has got to.
 *
 * The launcher's own vocabulary for it, so nothing above the engine names the
 * library that produced it. Every engine maps its own states onto these seven, and
 * the mapping is a `when` with no `else`, so a state added on either side stops the
 * build instead of falling through to a guess.
 */
public sealed interface EngineState {
    /** Opened but not yet decoding. */
    public data object Opening : EngineState

    /** A jump is in flight. Sounds like playing from the outside. */
    public data object Seeking : EngineState

    public data object Playing : EngineState

    public data object Paused : EngineState

    /** The last sample has played. The engine is still alive and can be rewound. */
    public data object Ended : EngineState

    /** Terminal. The open or the decode failed for [cause]. */
    public data class Failed(val cause: Throwable) : EngineState

    /** Released. */
    public data object Closed : EngineState
}

/**
 * Opens a file, or throws the way the real one throws.
 *
 * A function rather than a class because that is all it is, and because the
 * failure modes are part of the contract: an ordinary exception for a file that
 * will not open, a [LinkageError] for a natives bundle that will not load, and an
 * [EngineUnavailable] for an engine that is switched off. The caller catches all
 * three, so a stand-in that wants to exercise any of those paths simply throws it.
 *
 * Whatever the engine needs beside the file, an output stream among it, is the
 * factory's to arrange and to give back when the open fails. The player above has
 * no way to know what that is.
 */
public fun interface PlaybackEngines {
    public fun open(file: Path): PlaybackEngine
}

/**
 * The engine is switched off, which is a different answer from a file that would
 * not open: nothing is wrong with the track and pressing play again will not help.
 */
public class EngineUnavailable(message: String) : Exception(message)
