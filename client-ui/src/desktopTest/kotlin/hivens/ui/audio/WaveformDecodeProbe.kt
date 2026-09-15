package hivens.ui.audio

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one thing the unit tests cannot answer: whether a sink that never blocks
 * makes the decoder run at its own speed.
 *
 * The envelope rests on that property. The skinema this builds against documents
 * its write as blocking until the device took the bytes and stops there; the
 * property is stated outright only in a later version. So it is measured here
 * rather than assumed, against a file whose contents are known exactly, which
 * also makes the envelope itself checkable end to end instead of merely plausible.
 *
 * Off by default because it needs the FFmpeg natives, and a hardware suite that
 * skips looks exactly like one that passes:
 *
 * ```
 * ./gradlew :client-ui:desktopTest --tests '*WaveformDecodeProbe*' -Dnexira.probe.audio=1
 * ```
 */
class WaveformDecodeProbe {

    private val switch: String? = System.getProperty(PROPERTY) ?: System.getenv(ENV)
    private val enabled = switch != null

    /** The switch doubles as the length to measure, so the cost can be seen to scale. */
    private val seconds = switch?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_SECONDS

    @Test
    fun `a whole file is measured faster than it would play`() {
        if (!enabled) {
            println("WaveformDecodeProbe not run. Pass -D$PROPERTY=1 to run it.")
            return
        }
        val file = writeWav(seconds)
        try {
            val started = System.nanoTime()
            val waveform = runBlocking { computeWaveform(file, buckets = BUCKETS) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L

            assertNotNull(waveform, "the probe file did not decode")
            println("decoded ${seconds}s of audio in ${elapsedMs}ms (${"%.1f".format(seconds * 1000.0 / elapsedMs)}x realtime)")

            assertTrue(
                elapsedMs < seconds * 1000L / 4,
                "a non-blocking sink did not free the decoder: ${elapsedMs}ms for ${seconds}s of audio",
            )

            // The file is four quarters of known amplitude, so the envelope is
            // checkable rather than merely present. Sampled in the middle of each
            // quarter, away from the boundaries a bucket can straddle.
            assertEquals(BUCKETS, waveform.size)
            val quarter = BUCKETS / 4
            listOf(1.0f, 0.25f, 0.75f, 0.0f).forEachIndexed { index, expected ->
                val at = quarter * index + quarter / 2
                assertEquals(expected, waveform[at], 0.02f, "quarter $index, bucket $at")
            }
        } finally {
            file.deleteIfExists()
        }
    }

    /** A 44.1 kHz S16LE stereo WAV of four equal parts at known amplitudes. */
    private fun writeWav(seconds: Int): Path {
        val frames = RATE * seconds
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val amplitudes = listOf(1.0f, 0.25f, 0.75f, 0.0f)
        for (frame in 0 until frames) {
            val amplitude = amplitudes[(frame * 4 / frames).coerceAtMost(3)]
            // A square wave rather than a sine: every sample sits at the peak, so
            // a window's peak is the amplitude exactly and the assertion needs no
            // tolerance for where the window fell.
            val value = (if ((frame / 64) % 2 == 0) amplitude else -amplitude) * 32767f
            val sample = value.toInt().toShort()
            pcm.putShort(sample)
            pcm.putShort(sample)
        }
        val data = pcm.array()

        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun le16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())

        ascii("RIFF"); le32(36 + data.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(2); le32(RATE)
        le32(RATE * 4); le16(4); le16(16)
        ascii("data"); le32(data.size)
        out.write(data)

        val file = Files.createTempFile("nexira-waveform", ".wav")
        Files.write(file, out.toByteArray())
        return file
    }

    private companion object {
        const val PROPERTY = "nexira.probe.audio"
        const val ENV = "NEXIRA_PROBE_AUDIO"
        const val RATE = 44_100
        const val DEFAULT_SECONDS = 20
        const val BUCKETS = 256
    }
}
