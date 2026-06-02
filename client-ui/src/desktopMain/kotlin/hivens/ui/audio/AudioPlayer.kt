package hivens.ui.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.io.path.name
import kotlin.math.log10

// In-process audio playback for the MusicPlayerWidget. Pure
// javax.sound.sampled -- WAV / AU / AIFF / SND supported out of the
// JDK box. MP3 / OGG / FLAC require a codec SPI; deferred to the
// planned Skinema library which will wrap FFmpeg via Panama and
// cover both audio and video. Until Skinema lands, the user can
// convert MP3 -> WAV externally or use lossless source files.
//
// Single track at a time; opening a new file stops the previous
// playback. State exposed as a StateFlow so widgets recompose on
// position updates (1Hz from the playback loop).
class AudioPlayer(private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(AudioPlayer::class.java)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var line: SourceDataLine? = null
    private var playbackJob: Job? = null
    private var currentFile: Path? = null
    private var currentFormat: AudioFormat? = null
    private var totalFrames: Long = 0L
    // Frame offset to start the next play() at -- nonzero only when we
    // resumed from pause. Reset when a new file opens, stop fires, or
    // playback ends naturally.
    private var resumeFrameOffset: Long = 0L

    fun open(file: Path) {
        stop()
        resumeFrameOffset = 0L
        currentFile = file
        try {
            val stream = AudioSystem.getAudioInputStream(file.toAbsolutePath().toFile())
            stream.close()
            // Probe format + duration without holding the stream open.
            val baseFormat = AudioSystem.getAudioFileFormat(file.toAbsolutePath().toFile())
            currentFormat = baseFormat.format
            totalFrames = baseFormat.frameLength.toLong()
            val durationMs = if (baseFormat.format.frameRate > 0f && totalFrames > 0L) {
                (totalFrames * 1000L / baseFormat.format.frameRate.toLong()).coerceAtLeast(0L)
            } else 0L
            _state.value = PlaybackState.Ready(
                file       = file,
                positionMs = 0L,
                durationMs = durationMs,
            )
        } catch (e: UnsupportedAudioFileException) {
            log.warn("Unsupported audio format: ${file.name}", e)
            _state.value = PlaybackState.Error(file, AudioError.UnsupportedFormat)
        } catch (e: Exception) {
            log.error("Failed to open audio file ${file.name}", e)
            _state.value = PlaybackState.Error(file, AudioError.OpenFailed)
        }
    }

    fun play() {
        val file = currentFile ?: return
        val current = _state.value
        if (current is PlaybackState.Playing) return
        val format = currentFormat ?: return

        val startOffset = resumeFrameOffset
        val frameRate = format.frameRate
        val initialDurationMs = if (frameRate > 0f && totalFrames > 0L) {
            totalFrames * 1000L / frameRate.toLong()
        } else 0L
        val initialPositionMs = if (frameRate > 0f) {
            startOffset * 1000L / frameRate.toLong()
        } else 0L
        // Set Playing up-front so pause() racing against the coroutine
        // has a clear sentinel to flip. The position-update loop below
        // only emits Playing when the current state is still Playing --
        // any pause() that lands during a blocking newLine.write will
        // not be overwritten by a trailing emit.
        _state.value = PlaybackState.Playing(file, initialPositionMs, initialDurationMs)

        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            val newStream = AudioSystem.getAudioInputStream(file.toAbsolutePath().toFile())
            try {
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val newLine = AudioSystem.getLine(info) as SourceDataLine
                newLine.open(format)
                applyVolumeTo(newLine)
                newLine.start()
                line = newLine

                val frameSize = format.frameSize
                if (startOffset > 0L) {
                    skipExact(newStream, startOffset * frameSize.toLong())
                }
                val buffer = ByteArray(4096)
                var bytesRead = newStream.read(buffer)
                var framesPlayed = startOffset
                while (isActive && bytesRead != -1) {
                    newLine.write(buffer, 0, bytesRead)
                    framesPlayed += bytesRead / frameSize
                    val positionMs = if (format.frameRate > 0f) {
                        (framesPlayed * 1000L / format.frameRate.toLong())
                    } else 0L
                    val durationMs = if (format.frameRate > 0f && totalFrames > 0L) {
                        totalFrames * 1000L / format.frameRate.toLong()
                    } else 0L
                    // Atomic update: a pause() landing between the read
                    // and the write would flip state to Paused. Without
                    // CAS, this branch could overwrite Paused back to
                    // Playing (the original double-click bug).
                    _state.update { snapshot ->
                        if (snapshot is PlaybackState.Playing) {
                            PlaybackState.Playing(file, positionMs, durationMs)
                        } else snapshot
                    }
                    bytesRead = newStream.read(buffer)
                }
                // Cleanup is best-effort: pause()/stop() may have already
                // stopped+flushed (or closed) the line. drain on a
                // stopped/flushed line returns immediately, but a second
                // close throws -- runCatching keeps us out of the Error
                // branch on that benign race.
                runCatching { newLine.drain() }
                runCatching { newLine.close() }
                if (line === newLine) line = null
                if (isActive && _state.value is PlaybackState.Playing) {
                    resumeFrameOffset = 0L
                    _state.value = PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L)
                }
            } catch (e: LineUnavailableException) {
                log.error("Audio line unavailable for ${file.name}", e)
                _state.value = PlaybackState.Error(file, AudioError.DeviceBusy)
            } catch (e: Exception) {
                log.error("Playback failed for ${file.name}", e)
                _state.value = PlaybackState.Error(file, AudioError.PlaybackFailed)
            } finally {
                runCatching { newStream.close() }
            }
        }
    }

    fun pause() {
        // javax.sound.sampled has no pause primitive. Stop and flush
        // the line to unblock any pending newLine.write, then update
        // state via CAS so a trailing emit from the play coroutine
        // (already past its is-Playing check) cannot flip us back.
        // Real seek lands with Skinema (FFmpeg via Panama).
        val current = _state.value
        if (current !is PlaybackState.Playing) return
        val frameRate = currentFormat?.frameRate ?: 0f
        resumeFrameOffset = if (frameRate > 0f) {
            (current.positionMs * frameRate.toDouble() / 1000.0).toLong()
        } else 0L
        _state.update { snapshot ->
            if (snapshot is PlaybackState.Playing) {
                PlaybackState.Paused(snapshot.file, snapshot.positionMs, snapshot.durationMs)
            } else snapshot
        }
        // Unblock the writer and cancel cleanup. Line is owned by the
        // play coroutine -- it drains+closes in its finally path; we
        // only signal here.
        line?.stop()
        line?.flush()
        playbackJob?.cancel()
        playbackJob = null
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        line?.stop()
        line?.close()
        line = null
        resumeFrameOffset = 0L
        val current = _state.value
        if (current is PlaybackState.Playing || current is PlaybackState.Paused) {
            val file = currentFile
            if (file != null) {
                _state.value = PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L)
            } else {
                _state.value = PlaybackState.Idle
            }
        }
    }

    // Linear 0..1. The line's MASTER_GAIN control is preferred (every
    // SourceDataLine on every JDK platform supports it). VOLUME falls
    // back where the device exposes one but not the other.
    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _volume.value = clamped
        line?.let { applyVolumeTo(it) }
    }

    private fun applyVolumeTo(target: SourceDataLine) {
        runCatching {
            when {
                target.isControlSupported(FloatControl.Type.MASTER_GAIN) -> {
                    val control = target.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                    control.value = linearToDb(_volume.value, control.minimum, control.maximum)
                }
                target.isControlSupported(FloatControl.Type.VOLUME) -> {
                    val control = target.getControl(FloatControl.Type.VOLUME) as FloatControl
                    control.value = _volume.value.coerceIn(control.minimum, control.maximum)
                }
            }
        }.onFailure { log.warn("Failed to apply volume to audio line", it) }
    }

    private fun linearToDb(linear: Float, min: Float, max: Float): Float {
        if (linear <= 0f) return min
        val db = (20.0 * log10(linear.toDouble())).toFloat()
        return db.coerceIn(min, max)
    }

    // AudioInputStream.skip is not contractually exact -- platforms can
    // return 0 if the stream is unbuffered or refuse to skip past a
    // mark. Loop until satisfied; fall back to read-and-discard when
    // skip stops making progress.
    private fun skipExact(stream: AudioInputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val n = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n <= 0) return
            remaining -= n
        }
    }
}

sealed class PlaybackState {
    abstract val file: Path?

    object Idle : PlaybackState() {
        override val file: Path? = null
    }

    data class Ready(
        override val file: Path,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Playing(
        override val file: Path,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Paused(
        override val file: Path,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Error(
        override val file: Path,
        val reason: AudioError,
    ) : PlaybackState()
}

enum class AudioError { UnsupportedFormat, OpenFailed, DeviceBusy, PlaybackFailed }
