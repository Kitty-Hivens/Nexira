package hivens.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.logging.Redactor
import hivens.launcher.platform.PlatformPaths
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.*

private val log = LoggerFactory.getLogger("GameConsoleService")

enum class LogType { INFO, ERROR, WARN, DIVIDER }

data class LogEntry(
    val text: String,
    val type: LogType,
    val timestamp: String = SimpleDateFormat("HH:mm:ss").format(Date())
)

object GameConsoleService {
    val logs = mutableStateListOf<LogEntry>()

    var shouldShowConsole by mutableStateOf(false)

    // Configurable max lines
    var maxLines: Int = 2000

    private var sessionWriter: BufferedWriter? = null
    private val fileDateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

    private fun logsDir(): File =
        PlatformPaths.system().logsDir.toFile().also { it.mkdirs() }

    fun startSession() {
        // Close previous session writer
        sessionWriter?.close()
        sessionWriter = null

        // Add visual divider
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        logs.add(LogEntry("─────────── Session started $time ───────────", LogType.DIVIDER, time))

        // Open new auto-save file
        try {
            val fileName = "game-output-${fileDateFmt.format(Date())}.log"
            sessionWriter = BufferedWriter(FileWriter(File(logsDir(), fileName), true))
        } catch (e: Exception) {
            log.warn("Could not open per-session game-output log; in-memory console still works", e)
        }
    }

    fun append(text: String, type: LogType = LogType.INFO) {
        if (logs.size >= maxLines) {
            logs.removeAt(0)
        }
        // Redact at append time: the in-memory buffer (which feeds ConsoleWindow,
        // the auto-save file, and `Save to file` exports) NEVER carries raw
        // accessTokens / passwords / UUIDs. Means a screenshot of the console or
        // a Ctrl+C copy of a log line is safe to share for support.
        val entry = LogEntry(Redactor.redact(text), type)
        logs.add(entry)

        // Auto-save to disk
        try {
            sessionWriter?.apply {
                write("[${entry.timestamp}] ${entry.text}")
                newLine()
                flush()
            }
        } catch (e: Exception) {
            // Single line per occurrence; this fires on EVERY append so verbose
            // levels would flood `launcher.log` if the writer is permanently broken.
            log.debug("Failed to mirror console entry to per-session file", e)
        }
    }

    fun saveToFile(): File? {
        return try {
            val fileName = "console-export-${fileDateFmt.format(Date())}.log"
            val file = File(logsDir(), fileName)
            file.bufferedWriter().use { writer ->
                logs.forEach { entry ->
                    if (entry.type == LogType.DIVIDER) {
                        writer.write(entry.text)
                    } else {
                        writer.write("[${entry.timestamp}] ${entry.text}")
                    }
                    writer.newLine()
                }
            }
            file
        } catch (_: Exception) { null }
    }

    fun clear() {
        logs.clear()
        sessionWriter?.close()
        sessionWriter = null
    }

    fun show() { shouldShowConsole = true }
    fun hide() { shouldShowConsole = false }
}
