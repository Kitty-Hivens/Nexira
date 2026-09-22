package hivens.ui.audio

import dev.hivens.skinema.audio.PcmEncoding
import dev.hivens.skinema.audio.PcmFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The arithmetic under the envelope, without a decoder.
 *
 * The decode itself needs FFmpeg natives and a real file, so what is asserted
 * here is everything that is not the decode: the endianness, where a window
 * ends, what a partial window does, how a span collapses into a bucket, and
 * which formats the sink refuses. Each of those is a place a silent one-off
 * error would draw a plausible waveform of the wrong track.
 */
class WaveformTest {

    /** Interleaved S16LE bytes from sample values. */
    private fun s16(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Interleaved F32LE bytes from sample values in -1..1. */
    private fun f32(vararg samples: Float): ByteArray {
        val out = ByteArray(samples.size * 4)
        samples.forEachIndexed { i, v ->
            val bits = v.toRawBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `a sample is read little endian and signed`() {
        val fold = PeakFold(windowSamples = 1, encoding = PcmEncoding.S16LE)
        // 0x4000 is half of full scale, and the negative of it must measure the same.
        fold.accept(s16(0x4000, -0x4000), 0, 4)
        val peaks = fold.finish()
        assertEquals(2, peaks.size)
        assertEquals(0.5f, peaks[0], 1e-6f)
        assertEquals(0.5f, peaks[1], 1e-6f)
    }

    @Test
    fun `a float sample is read at its own width and normalised`() {
        // The regression this guards: most music decodes to F32LE, and reading a
        // float as a short is noise, so the strip drew a flat line for every
        // ordinary track until the fold learned the encoding.
        val fold = PeakFold(windowSamples = 1, encoding = PcmEncoding.F32LE)
        fold.accept(f32(0.5f, -0.5f, 1.0f), 0, 12)
        val peaks = fold.finish()
        assertEquals(3, peaks.size)
        assertEquals(0.5f, peaks[0], 1e-6f)
        assertEquals(0.5f, peaks[1], 1e-6f)
        assertEquals(1.0f, peaks[2], 1e-6f)
    }

    @Test
    fun `the most negative sample is full scale and does not overflow`() {
        val fold = PeakFold(windowSamples = 1, encoding = PcmEncoding.S16LE)
        fold.accept(s16(-32768), 0, 2)
        assertEquals(1.0f, fold.finish()[0], 1e-6f)
    }

    @Test
    fun `a window is the loudest sample in it, whichever channel carried it`() {
        // Four samples per window: a quiet left channel and a loud right one.
        val fold = PeakFold(windowSamples = 4, encoding = PcmEncoding.S16LE)
        fold.accept(s16(100, 32767, 100, 200), 0, 8)
        val peaks = fold.finish()
        assertEquals(1, peaks.size)
        assertTrue(peaks[0] > 0.999f, "the loud sample decides the window, got ${peaks[0]}")
    }

    @Test
    fun `windows close on their boundary and not on the write boundary`() {
        val fold = PeakFold(windowSamples = 2, encoding = PcmEncoding.S16LE)
        // Three writes that do not line up with the window: 1, 3 and 2 samples.
        fold.accept(s16(0), 0, 2)
        fold.accept(s16(32767, 0, 0), 0, 6)
        fold.accept(s16(0, 16384), 0, 4)
        val peaks = fold.finish()
        // Six samples in windows of two: three full windows.
        assertEquals(3, peaks.size)
        assertTrue(peaks[0] > 0.999f, "the loud sample belongs to the first window")
        assertEquals(0f, peaks[1])
        assertEquals(0.5f, peaks[2], 1e-4f)
    }

    @Test
    fun `a partial window is kept rather than dropped`() {
        val fold = PeakFold(windowSamples = 4, encoding = PcmEncoding.S16LE)
        fold.accept(s16(32767), 0, 2)
        val peaks = fold.finish()
        assertEquals(1, peaks.size, "the tail of a track is a window too")
        assertTrue(peaks[0] > 0.999f)
    }

    @Test
    fun `an odd trailing byte is not read as a sample`() {
        val fold = PeakFold(windowSamples = 1, encoding = PcmEncoding.S16LE)
        val data = s16(0x4000) + byteArrayOf(0x7F)
        fold.accept(data, 0, data.size)
        assertEquals(1, fold.finish().size)
    }

    @Test
    fun `offset and length are honoured`() {
        val fold = PeakFold(windowSamples = 1, encoding = PcmEncoding.S16LE)
        val data = s16(32767, 0x4000, 32767)
        // Only the middle sample.
        fold.accept(data, 2, 2)
        val peaks = fold.finish()
        assertEquals(1, peaks.size)
        assertEquals(0.5f, peaks[0], 1e-6f)
    }

    @Test
    fun `nothing written is no windows`() {
        assertEquals(0, PeakFold(windowSamples = 4, encoding = PcmEncoding.S16LE).finish().size)
    }

    @Test
    fun `resampling takes the loudest of each span`() {
        val peaks = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f, 0.8f, 0.1f)
        assertContentEquals(floatArrayOf(0.9f, 0.3f, 0.8f), resampleTo(peaks, 3))
    }

    @Test
    fun `resampling to the same count is the same values`() {
        val peaks = floatArrayOf(0.1f, 0.9f, 0.2f)
        assertContentEquals(peaks, resampleTo(peaks, 3))
    }

    @Test
    fun `more buckets than windows repeats rather than inventing`() {
        val peaks = floatArrayOf(0.25f, 0.75f)
        val out = resampleTo(peaks, 6)
        assertEquals(6, out.size)
        assertTrue(out.all { it == 0.25f || it == 0.75f }, "no value was invented: ${out.toList()}")
        assertEquals(0.25f, out.first())
        assertEquals(0.75f, out.last())
    }

    @Test
    fun `an empty envelope resamples to nothing`() {
        assertEquals(0, resampleTo(FloatArray(0), 64).size)
    }

    @Test
    fun `a bucket count that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { resampleTo(floatArrayOf(1f), 0) }
    }

    /**
     * The shape the envelope pass has always assumed, now stated rather than
     * implied: skinema hands the sink a whole format since 0.8.2, and these
     * tests pin the stride arithmetic, so the format they pass has to be the one
     * that arithmetic was written for.
     */
    private fun stereo16(sampleRate: Int) = PcmFormat(
        sampleRate = sampleRate,
        channels = 2,
        layout = "stereo",
        encoding = PcmEncoding.S16LE,
        significantBits = 16,
    )

    // ---- the sink -------------------------------------------------------

    @Test
    fun `reopening drops what the previous stream gathered`() {
        val sink = EnvelopeSink()
        sink.open(stereo16(44_100))
        sink.write(s16(32767, 32767), 0, 4)
        assertTrue(sink.framePosition() > 0)

        sink.open(stereo16(44_100))
        assertEquals(0L, sink.framePosition(), "a reopen restarts the clock at zero")
        assertEquals(0, sink.peaks().size, "and does not splice the old stream onto the new one")
    }

    @Test
    fun `the frame position counts frames rather than bytes`() {
        val sink = EnvelopeSink()
        sink.open(stereo16(44_100))
        // Four stereo frames: sixteen bytes at two channels of two bytes.
        sink.write(s16(0, 0, 0, 0, 0, 0, 0, 0), 0, 16)
        assertEquals(4L, sink.framePosition())
    }

    @Test
    fun `a write before any open is dropped rather than throwing`() {
        // close() can arrive from the watchdog while a write is in flight, and a
        // sink that threw here would turn a rescue into a failure.
        val sink = EnvelopeSink()
        sink.write(s16(32767), 0, 2)
        sink.close()
        assertEquals(0, sink.peaks().size)
    }

    @Test
    fun `a silent track is reported as silent rather than as no track`() {
        val waveform = Waveform(FloatArray(8))
        assertTrue(waveform.isSilent)
        assertEquals(8, waveform.size)
    }
}
