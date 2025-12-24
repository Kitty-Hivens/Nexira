package hivens.ui.utils

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.*

enum class LogType { INFO, ERROR, WARN }

data class LogEntry(
    val text: String,
    val type: LogType,
    val timestamp: String = SimpleDateFormat("HH:mm:ss").format(Date())
)

object GameConsoleService {
    val logs = mutableStateListOf<LogEntry>()
    
    // Флаг видимости окна
    var shouldShowConsole by mutableStateOf(false)

    fun append(text: String, type: LogType = LogType.INFO) {
        if (logs.size > 2000) {
            logs.removeAt(0)
        }
        logs.add(LogEntry(text, type))
    }

    fun clear() {
        logs.clear()
    }
    
    fun show() {
        shouldShowConsole = true
    }
    
    fun hide() {
        shouldShowConsole = false
    }
}
