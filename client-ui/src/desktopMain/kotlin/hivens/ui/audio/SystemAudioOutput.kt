package hivens.ui.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.AudioBackends
import dev.hivens.skinema.audio.PcmSink
import org.slf4j.LoggerFactory

/**
 * Where the launcher's sound leaves, when the system will have it by name.
 *
 * Without this skinema opens a line for itself through JavaSound, and what the
 * desktop's mixer shows is an anonymous row labelled with the JVM's process
 * name: no icon, no media role, and a volume slider that belongs to the whole
 * virtual machine rather than to the music. Through libsound it is a stream
 * called Nexira that a person can find, turn down, move to another device, or
 * point an EasyEffects rule at, and on Linux it speaks PipeWire natively rather
 * than through its PulseAudio server.
 *
 * One backend for the process and a sink per track, which is the shape both
 * libraries expect: a backend is a connection to the sound server, and a sink is
 * one stream on it. The player hands each sink to skinema and skinema closes it,
 * so nothing here tracks them.
 *
 * Null is an ordinary answer at every step. A machine with no device at all, a
 * backend that will not load its system library, a sink the server refuses: each
 * of those simply means skinema opens its own line as it always did. Sound is the
 * feature, identity in the mixer is the improvement, and losing the second must
 * never cost the first.
 */
class SystemAudioOutput : AutoCloseable {

    private val log = LoggerFactory.getLogger(SystemAudioOutput::class.java)

    private var backend: AudioBackend? = null
    private var attempted = false
    private val lock = Any()

    /**
     * A sink for one track, or null to let skinema open its own line.
     *
     * The backend is opened on the first call rather than at construction: it is
     * a connection to the sound server, and a launcher that never plays anything
     * has no business holding one.
     */
    fun sink(): PcmSink? {
        val open = backendOrNull() ?: return null
        return try {
            SkinemaAdapter(
                open.createSink(
                    SinkConfig(
                        applicationName = APPLICATION,
                        applicationId = APPLICATION_ID,
                        iconName = ICON,
                        mediaRole = MediaRole.MUSIC,
                    ),
                ),
            )
        } catch (e: Exception) {
            log.warn("Could not create a system audio stream; falling back to the player's own line", e)
            null
        }
    }

    private fun backendOrNull(): AudioBackend? = synchronized(lock) {
        if (attempted) return backend
        attempted = true
        backend = try {
            AudioBackends.open(APPLICATION)
        } catch (e: Exception) {
            log.warn("No system audio backend; falling back to the player's own line", e)
            null
        } catch (e: LinkageError) {
            // The same narrow catch the player makes around skinema, for the same
            // reason: a Panama binding whose system library will not load fails as
            // an Error, and a launcher that cannot reach libpulse should play
            // through the fallback rather than refuse to start.
            log.warn("No system audio backend; falling back to the player's own line", e)
            null
        }
        backend?.let { log.info("System audio output ready: {}", it.javaClass.simpleName) }
        backend
    }

    override fun close() {
        synchronized(lock) {
            runCatching { backend?.close() }
            backend = null
        }
    }

    private companion object {
        const val APPLICATION = "Nexira"
        const val APPLICATION_ID = "dev.hivens.nexira"

        /** A freedesktop icon name, which is what a mixer row draws beside the name. */
        const val ICON = "audio-x-generic"
    }
}

/**
 * skinema's seam on one side, libsound's sink on the other.
 *
 * The two contracts line up method for method, which is not luck: both were
 * written around the same question, how many frames the device has actually
 * played, because that number is the clock a player's whole timeline rides on.
 * So this carries no state and corrects nothing.
 *
 * The one thing it states is the shape. skinema's seam is S16LE interleaved
 * stereo and says so in its own documentation, so the format is built here rather
 * than negotiated: every libsound backend must accept that shape, which makes the
 * open the one call in the chain that cannot fail for want of a format.
 */
private class SkinemaAdapter(private val sink: AudioSink) : PcmSink {

    override fun open(sampleRate: Int) {
        sink.open(AudioFormat(sampleRate, CHANNELS, PcmEncoding.S16LE))
    }

    override fun write(data: ByteArray, offset: Int, length: Int) = sink.write(data, offset, length)

    override fun stop() = sink.stop()

    override fun start() = sink.start()

    override fun flush() = sink.flush()

    override fun framePosition(): Long = sink.framePosition()

    override fun setVolume(volume: Float) = sink.setVolume(volume)

    // Idempotent on both sides: skinema's watchdog closes a sink to break a write
    // that nothing will finish, and the audio thread closes it again on its way
    // out.
    override fun close() = sink.close()

    private companion object {
        const val CHANNELS = 2
    }
}
