package hivens.ui.audio

import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.player.VideoPlayer
import hivens.ui.diag.SkinemaGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A track reduced to one peak magnitude per bucket, each 0..1.
 *
 * The shape a waveform strip draws. Peak rather than RMS because peak is what
 * gives a track its recognisable outline: an RMS curve of the same music is
 * smoother and reads as loudness rather than as this particular song. Mirroring
 * it about the centre line is a drawing decision and is not baked in here.
 */
class Waveform internal constructor(private val values: FloatArray) {

    val size: Int get() = values.size

    operator fun get(bucket: Int): Float = values[bucket]

    /** True for a track that decoded but carried no sound above silence. */
    val isSilent: Boolean get() = values.all { it == 0f }
}

/**
 * Decodes [file] from end to end and answers its envelope, or null.
 *
 * The whole file is read at decode speed rather than at playing speed, because
 * skinema paces playback by the sink's blocking write and this sink never
 * blocks. Measured through `WaveformDecodeProbe` on a synthetic WAV: 20 s of
 * audio in 997 ms, 60 s in 951 ms, 180 s in 934 ms. The cost is flat rather than
 * proportional, and nearly all of it is the player's own teardown, which is
 * bounded at a second by its contract. `closeAsync` is the lever if a consumer
 * ever needs the answer sooner than that.
 *
 * Null for every way this can fail to produce an answer: the media module
 * disabled, a file that will not open, a decode that reports [VideoPlayer.State.Failed],
 * a stall that runs past the timeout, or a file with no audio in it. A caller
 * draws no strip rather than an empty one, because an envelope of zeroes and a
 * track of silence look the same on screen and are not the same thing.
 *
 * Runs on [Dispatchers.IO] and must not be called from the composition: this
 * opens a decode thread and closing it blocks.
 */
suspend fun computeWaveform(file: Path, buckets: Int = DEFAULT_BUCKETS): Waveform? {
    require(buckets > 0) { "buckets must be positive, got $buckets" }
    if (!SkinemaGate.enabled) {
        WaveformLog.log.warn("Waveform refused: the skinema module is disabled")
        return null
    }
    return withContext(Dispatchers.IO) { decodeEnvelope(file, buckets) }
}

private object WaveformLog {
    val log = LoggerFactory.getLogger("hivens.ui.audio.Waveform")
}

private suspend fun decodeEnvelope(file: Path, buckets: Int): Waveform? {
    val sink = EnvelopeSink()
    val player = try {
        // loop = false is load bearing. The default turns the lap at the end of
        // the file, and a sink that never blocks would then read the same track
        // forever at decode speed.
        VideoPlayer(path = file, loop = false, audio = true, sink = sink)
    } catch (e: Exception) {
        WaveformLog.log.warn("Waveform open failed for {}", file, e)
        return null
    } catch (e: LinkageError) {
        // The same narrow catch AudioPlayer makes, for the same reason: a
        // natives bundle that will not load is a refusal, not a crash.
        WaveformLog.log.warn("Waveform open failed for {}", file, e)
        return null
    }

    val finished = try {
        withTimeoutOrNull(DECODE_TIMEOUT) { awaitEnd(player, file) } ?: false
    } finally {
        // Closing is what guarantees nothing is still writing into the sink, so
        // the fold is only safe to read after this returns. It is bounded at a
        // second by the player's own contract.
        runCatching { player.close() }
    }
    if (!finished) return null

    val peaks = sink.peaks()
    if (peaks.isEmpty()) {
        WaveformLog.log.info("No audio decoded from {}", file)
        return null
    }
    return Waveform(resampleTo(peaks, buckets))
}

/** True once the file has played out, false for a decode that failed. */
private suspend fun awaitEnd(player: VideoPlayer, file: Path): Boolean {
    while (coroutineContext.isActive) {
        when (val st = player.state) {
            VideoPlayer.State.Ended -> return true
            is VideoPlayer.State.Failed -> {
                WaveformLog.log.warn("Waveform decode failed for {}", file, st.cause)
                return false
            }
            // Closed cannot arrive from here, since nothing else holds this
            // player, but a state machine read in a loop is answered in full.
            VideoPlayer.State.Closed -> return false
            else -> delay(POLL_MS.milliseconds)
        }
    }
    return false
}

/**
 * The sink that measures instead of playing.
 *
 * The seam now carries five encodings and any channel count, and an open states
 * both. The fold below reads each sample at the width the encoding names and
 * normalises it to 0..1, so a float track and an integer one draw at the same
 * scale. It measures in samples rather than in frames, so the channel count never
 * enters the arithmetic.
 */
internal class EnvelopeSink : PcmSink {

    // Read from several threads during a write, so it is never allowed behind a
    // lock the write holds. Nothing here takes a lock at all: the calls that
    // touch the fold arrive on the audio thread alone and in order.
    private val played = AtomicLong(0)

    private var fold: PeakFold? = null

    /**
     * Read from the format the stream declares rather than assumed. The constant
     * it replaces was two channels times two bytes, which is right for S16
     * stereo and quietly wrong for anything else -- and a frame count divided by
     * the wrong stride is a clock that drifts rather than an error that shows.
     */
    private var bytesPerFrame = DEFAULT_BYTES_PER_FRAME

    override fun open(format: PcmFormat) {
        // Reopening replaces the stream and restarts the clock at zero, so what
        // was gathered for the previous one goes with it rather than being
        // spliced onto what follows.
        played.set(0)
        bytesPerFrame = format.bytesPerFrame
        fold = PeakFold(WINDOW_FRAMES * format.channels, format.encoding)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        fold?.accept(data, offset, length)
        played.addAndGet((length / bytesPerFrame).toLong())
    }

    // Everything accepted has been played, because this sink is the device. The
    // warning in the contract is about a picture running ahead of its sound, and
    // there is no picture on this path.
    override fun framePosition(): Long = played.get()

    override fun setVolume(volume: Float) = Unit

    override fun stop() = Unit

    override fun start() = Unit

    // A seek would invalidate what has been gathered, and nothing seeks this
    // player. There is no device buffer to drop either way.
    override fun flush() = Unit

    override fun close() = Unit

    /** The windows gathered so far. Safe to read once the player has closed. */
    fun peaks(): FloatArray = fold?.finish() ?: FloatArray(0)
}

/**
 * Folds interleaved PCM bytes into one peak per window of samples, reading each
 * sample at the width and interpretation [encoding] names.
 *
 * Pure and separate from the sink for the reason the queue helpers are: the
 * arithmetic is the whole of the behaviour, it is easy to get the endianness or
 * the window boundary wrong by one, and testing it through a real decode would
 * put FFmpeg natives on the test path.
 *
 * Samples rather than frames, so the channel count never enters the arithmetic.
 * The peak of a window is the loudest sample in it whichever channel carried it,
 * normalised to 0..1 by the encoding's own range, which is what a single strip
 * draws. Reading S16LE bytes as though every stream were S16 was the bug this
 * fixed: most music decodes to F32LE (float), and a float read as a short is
 * noise, so the strip drew a flat line for every ordinary track.
 */
internal class PeakFold(private val windowSamples: Int, private val encoding: PcmEncoding) {

    private var values = FloatArray(INITIAL_WINDOWS)
    private var count = 0
    private var inWindow = 0
    private var peak = 0f
    private val sampleBytes = encoding.bytesPerSample

    fun accept(data: ByteArray, offset: Int, length: Int) {
        if (windowSamples <= 0 || sampleBytes <= 0) return
        var i = offset
        val end = offset + length
        // A trailing partial sample cannot land on a frame boundary and is dropped
        // rather than read past.
        while (i + sampleBytes <= end) {
            val magnitude = magnitudeAt(data, i)
            if (magnitude > peak) peak = magnitude
            i += sampleBytes
            if (++inWindow >= windowSamples) {
                add(peak)
                peak = 0f
                inWindow = 0
            }
        }
    }

    /** One little-endian sample's magnitude in 0..1, read as [encoding] names it. */
    private fun magnitudeAt(data: ByteArray, i: Int): Float = when (encoding) {
        PcmEncoding.U8 -> abs((data[i].toInt() and 0xFF) - 128) / U8_FULL_SCALE
        PcmEncoding.S16LE -> {
            // The high byte sign-extends on its own, so the value arrives signed.
            val s = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            abs(s) / S16_FULL_SCALE
        }
        PcmEncoding.S32LE -> {
            val s = (data[i].toInt() and 0xFF) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or
                (data[i + 3].toInt() shl 24)
            // abs as Long: abs(Int.MIN_VALUE) is negative on its own width.
            abs(s.toLong()) / S32_FULL_SCALE
        }
        PcmEncoding.F32LE -> {
            val bits = (data[i].toInt() and 0xFF) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or
                (data[i + 3].toInt() shl 24)
            abs(Float.fromBits(bits))
        }
        PcmEncoding.F64LE -> {
            var bits = 0L
            for (b in 0 until 8) bits = bits or ((data[i + b].toLong() and 0xFF) shl (b * 8))
            abs(Double.fromBits(bits)).toFloat()
        }
    }

    /** Closes the window in progress and answers every window gathered. */
    fun finish(): FloatArray {
        if (inWindow > 0) {
            add(peak)
            peak = 0f
            inWindow = 0
        }
        return values.copyOf(count)
    }

    private fun add(value: Float) {
        if (count == values.size) values = values.copyOf(values.size * 2)
        values[count++] = value
    }
}

/**
 * Reduces [peaks] to exactly [buckets] values, each the loudest of its span.
 *
 * Max rather than an average: averaging a span smooths the outline into a
 * loudness curve, and a strip drawn from it loses the transients that make a
 * track recognisable at a glance. Asking for more buckets than there are windows
 * repeats values rather than inventing them.
 */
internal fun resampleTo(peaks: FloatArray, buckets: Int): FloatArray {
    require(buckets > 0) { "buckets must be positive, got $buckets" }
    if (peaks.isEmpty()) return FloatArray(0)
    val out = FloatArray(buckets)
    for (i in 0 until buckets) {
        val from = (i.toLong() * peaks.size / buckets).toInt()
        val to = ((i + 1).toLong() * peaks.size / buckets).toInt()
        var max = 0f
        if (to > from) {
            for (k in from until to) if (peaks[k] > max) max = peaks[k]
        } else {
            max = peaks[from.coerceAtMost(peaks.lastIndex)]
        }
        out[i] = max
    }
    return out
}

/**
 * How many buckets a strip is drawn from by default.
 *
 * Wider than any slot a widget is given, so a strip is downsampled to the width
 * it has rather than stretched up to it, and small enough that a track costs a
 * few kilobytes to remember.
 */
const val DEFAULT_BUCKETS = 512

/**
 * Frames per window before the channel count multiplies it.
 *
 * At 44.1 kHz this is about 23 ms, which is finer than any strip is drawn and
 * coarse enough that a long track stays in tens of kilobytes on the way to being
 * resampled.
 */
private const val WINDOW_FRAMES = 1024

/** Assumed for the fallback stride only. Open reads the stream's real channel count. */
private const val CHANNELS = 2

/** Only a starting value: the stream states its own stride on open. */
private const val DEFAULT_BYTES_PER_FRAME = CHANNELS * 2

private const val INITIAL_WINDOWS = 1024

/**
 * 0..1 normalisation divisors, one per integer range the fold reads. The float
 * encodings arrive in range already and take none.
 */
private const val U8_FULL_SCALE = 128f
private const val S16_FULL_SCALE = 32768f
private const val S32_FULL_SCALE = 2147483648f

private const val POLL_MS = 20L

/**
 * A guard against a decode that never ends, not a budget. A whole file at decode
 * speed is a fraction of a second for a track and seconds for an audiobook.
 */
private val DECODE_TIMEOUT = 60.seconds

/** The stamp a cached envelope is keyed by, so an edited file is recomputed. */
internal fun waveformKey(file: Path, buckets: Int): String {
    val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
    val size = runCatching { Files.size(file) }.getOrDefault(0L)
    return "$file|$stamp|$size|$buckets"
}

/**
 * The last few envelopes, so a widget that remounts does not decode again.
 *
 * Keyed by the file's modification time and length as well as its path, because
 * a path is not a version: a track replaced in place would otherwise draw the
 * outline of the file that used to be there.
 *
 * Two callers asking for the same uncached track at the same moment decode it
 * twice. That is a wasted background decode rather than a wrong answer, and the
 * machinery to prevent it costs more than the case is worth while one player is
 * visible at a time.
 */
object WaveformCache {

    private val entries = object : LinkedHashMap<String, Waveform>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Waveform>): Boolean = size > CAPACITY
    }

    suspend fun of(file: Path, buckets: Int = DEFAULT_BUCKETS): Waveform? {
        val key = waveformKey(file, buckets)
        synchronized(entries) { entries[key] }?.let { return it }
        val computed = computeWaveform(file, buckets) ?: return null
        synchronized(entries) { entries[key] = computed }
        return computed
    }

    /** For a test, and for a settings action that drops what the player remembers. */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private const val CAPACITY = 8
}
