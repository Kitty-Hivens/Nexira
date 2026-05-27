package hivens.ui.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.io.path.name

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

    private var line: SourceDataLine? = null
    private var playbackJob: Job? = null
    private var currentFile: Path? = null
    private var currentFormat: AudioFormat? = null
    private var totalFrames: Long = 0L

    fun open(file: Path) {
        stop()
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
            _state.value = PlaybackState.Error(file, "Формат не поддерживается -- нужен WAV / AU / AIFF.")
        } catch (e: Exception) {
            log.error("Failed to open audio file ${file.name}", e)
            _state.value = PlaybackState.Error(file, e.message ?: "Не удалось открыть файл")
        }
    }

    fun play() {
        val file = currentFile ?: return
        val current = _state.value
        if (current is PlaybackState.Playing) return
        val format = currentFormat ?: return

        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            var newStream = AudioSystem.getAudioInputStream(file.toAbsolutePath().toFile())
            try {
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val newLine = AudioSystem.getLine(info) as SourceDataLine
                newLine.open(format)
                newLine.start()
                line = newLine

                val frameSize = format.frameSize
                val buffer = ByteArray(4096)
                var bytesRead = newStream.read(buffer)
                var framesPlayed = 0L
                while (isActive && bytesRead != -1) {
                    newLine.write(buffer, 0, bytesRead)
                    framesPlayed += bytesRead / frameSize
                    val positionMs = if (format.frameRate > 0f) {
                        (framesPlayed * 1000L / format.frameRate.toLong())
                    } else 0L
                    val durationMs = if (format.frameRate > 0f && totalFrames > 0L) {
                        totalFrames * 1000L / format.frameRate.toLong()
                    } else 0L
                    _state.value = PlaybackState.Playing(
                        file       = file,
                        positionMs = positionMs,
                        durationMs = durationMs,
                    )
                    bytesRead = newStream.read(buffer)
                }
                newLine.drain()
                newLine.close()
                line = null
                if (isActive) {
                    _state.value = PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L)
                }
            } catch (e: LineUnavailableException) {
                log.error("Audio line unavailable for ${file.name}", e)
                _state.value = PlaybackState.Error(file, "Аудиоустройство занято")
            } catch (e: Exception) {
                log.error("Playback failed for ${file.name}", e)
                _state.value = PlaybackState.Error(file, e.message ?: "Ошибка воспроизведения")
            } finally {
                runCatching { newStream.close() }
            }
        }
    }

    fun pause() {
        // javax.sound.sampled has no pause primitive; stop the line
        // and remember where we are. resume = play() (restarts the
        // stream from beginning -- limitation of the simple shape;
        // proper seek requires building a buffered position-stream).
        line?.stop()
        line?.close()
        line = null
        val current = _state.value
        if (current is PlaybackState.Playing) {
            _state.value = PlaybackState.Paused(current.file, current.positionMs, current.durationMs)
        }
        playbackJob?.cancel()
        playbackJob = null
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        line?.stop()
        line?.close()
        line = null
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

    fun shutdown() {
        stop()
        scope.cancel()
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
        val message: String,
    ) : PlaybackState()
}
