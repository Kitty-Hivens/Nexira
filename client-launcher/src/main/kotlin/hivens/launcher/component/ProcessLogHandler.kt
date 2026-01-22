package hivens.launcher.component

import hivens.core.data.LauncherLogType
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Component for asynchronous interception of I/O streams (IO Pipe).
 *
 * Provides redirection of `STDOUT` and `STDERR` of the child process
 * into a GUI callback function without blocking the main thread.
 */
internal class ProcessLogHandler {
    
    /**
     * Connects listeners to process threads.
     *
     * Creates demonic threads that live until the game process ends.
     *
     * @param process The target process.
     * @param onLog Log line handler function.
     */
    fun attach(process: Process, onLog: (String, LauncherLogType) -> Unit) {
        pipeOutput(process.inputStream, LauncherLogType.INFO, onLog)
        pipeOutput(process.errorStream, LauncherLogType.ERROR, onLog)
    }

    private fun pipeOutput(stream: InputStream, type: LauncherLogType, onLog: (String, LauncherLogType) -> Unit) {
        val reader = BufferedReader(InputStreamReader(stream))
        thread(isDaemon = true) {
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    
                    val finalType = when {
                        type == LauncherLogType.ERROR -> LauncherLogType.ERROR
                        text.contains("WARN", ignoreCase = true) -> LauncherLogType.WARN
                        text.contains("ERROR", ignoreCase = true) || text.contains("Exception", ignoreCase = true) -> LauncherLogType.ERROR
                        else -> LauncherLogType.INFO
                    }
                    
                    onLog(text, finalType)
                    
                    if (finalType == LauncherLogType.ERROR) System.err.println(text) else println(text)
                }
            } catch (_: Exception) {
                // Ignore EOF when terminating the process
            }
        }
    }
}
