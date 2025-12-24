package hivens.launcher.component

import hivens.core.data.LauncherLogType
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Компонент асинхронного перехвата потоков ввода-вывода (IO Pipe).
 *
 * Обеспечивает перенаправление `STDOUT` и `STDERR` дочернего процесса
 * в callback-функцию GUI без блокировки основного потока.
 */
internal class ProcessLogHandler {
    
    /**
     * Подключает слушатели к потокам процесса.
     *
     * Создает демонические потоки, живущие до завершения процесса игры.
     *
     * @param process Целевой процесс.
     * @param onLog Функция-обработчик строки лога.
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
                // Игнорируем EOF при завершении процесса
            }
        }
    }
}
