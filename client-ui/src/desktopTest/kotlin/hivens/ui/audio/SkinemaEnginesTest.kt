package hivens.ui.audio

import dev.hivens.skinema.audio.PcmFormat
import dev.hivens.skinema.audio.PcmSink
import hivens.ui.diag.SkinemaGate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the desktop engine factory does around the stream it builds for a track.
 *
 * The player above it knows nothing of streams, so the two halves of the contract
 * live here: a stream the engine refused to take is given back, and one it took is
 * left alone. The real player is replaced by a stand-in, so none of this needs
 * FFmpeg or a sound server.
 */
class SkinemaEnginesTest {

    /** A stream that records whether anybody gave it back. */
    private class FakeSink : PcmSink {
        var closed = false
        override fun open(format: PcmFormat) = Unit
        override fun write(data: ByteArray, offset: Int, length: Int) = Unit
        override fun stop() = Unit
        override fun start() = Unit
        override fun flush() = Unit
        override fun framePosition(): Long = 0L
        override fun setVolume(volume: Float) = Unit
        override fun close() { closed = true }
    }

    private class InertEngine : PlaybackEngine {
        override val state: EngineState = EngineState.Playing
        override val durationNanos: Long? = null
        override val tags: Map<String, String> = emptyMap()
        override val coverArt: ByteArray? = null
        override fun positionNanos(): Long = 0L
        override fun seek(nanos: Long) = Unit
        override fun resume() = Unit
        override fun pause() = Unit
        override fun setVolume(level: Float) = Unit
        override fun close() = Unit
    }

    private val file: Path = Path.of("/music/track.flac")

    @Test
    fun `a refused open gives back the stream it was handed`() {
        // The stream is built before the engine and passed into it, so an engine
        // that throws leaves it unreferenced and unclosed. What reliably throws
        // there is a natives bundle that will not load, and that does not get
        // better on the next press: every press would open another stream on the
        // sound server and drop it.
        val sink = FakeSink()
        val engines = SkinemaEngines(output = { sink }, construct = { _, _ -> throw UnsatisfiedLinkError("no libavcodec") })

        assertFailsWith<UnsatisfiedLinkError> { engines.open(file) }

        assertTrue(sink.closed, "a stream nothing took is the factory's to give back")
    }

    @Test
    fun `an ordinary failure gives the stream back too`() {
        val sink = FakeSink()
        val engines = SkinemaEngines(output = { sink }, construct = { _, _ -> throw IllegalStateException("no decoder") })

        assertFailsWith<IllegalStateException> { engines.open(file) }

        assertTrue(sink.closed)
    }

    @Test
    fun `an engine that takes the stream keeps it`() {
        // The other half, and the reason the close is on the failure path alone:
        // skinema closes the sink it was handed, so closing it here as well would
        // pull the device out from under a track that is playing.
        val sink = FakeSink()
        val handed = mutableListOf<PcmSink?>()
        val built = InertEngine()
        val engines = SkinemaEngines(output = { sink }, construct = { _, taken -> handed += taken; built })

        val opened = engines.open(file)

        assertSame(built, opened)
        assertEquals(listOf<PcmSink?>(sink), handed, "the stream reached the engine")
        assertTrue(!sink.closed, "and is the engine's to close from here")
    }

    @Test
    fun `without an output the engine is built with no stream`() {
        val handed = mutableListOf<PcmSink?>()
        val engines = SkinemaEngines(output = null, construct = { _, taken -> handed += taken; InertEngine() })

        engines.open(file)

        assertEquals(1, handed.size)
        assertNull(handed.single(), "skinema opens a line of its own")
    }

    @Test
    fun `a switched off module opens nothing and asks for no stream`() {
        val asked = mutableListOf<Unit>()
        val built = mutableListOf<Unit>()
        val engines = SkinemaEngines(
            output = { asked += Unit; FakeSink() },
            construct = { _, _ -> built += Unit; InertEngine() },
        )
        val was = SkinemaGate.enabled
        SkinemaGate.enabled = false
        try {
            assertFailsWith<EngineUnavailable> { engines.open(file) }
        } finally {
            SkinemaGate.enabled = was
        }

        assertTrue(asked.isEmpty(), "no stream is opened on a sound server for an engine that is off")
        assertTrue(built.isEmpty())
    }
}
