package hivens.ui.utils

import java.text.SimpleDateFormat
import java.util.Date

/**
 * Classification of a game-process log line. Drives colour + filter UX in
 * [hivens.ui.screens.ConsoleWindow]; [DIVIDER] is a synthetic entry the
 * launcher inserts itself (session boundaries), not a real game line.
 */
enum class LogType { INFO, ERROR, WARN, DIVIDER }

/**
 * One captured line in [GameConsoleService.logs]. The default-value
 * `timestamp` allocates a fresh SimpleDateFormat per construction so
 * per-line wall-clock stamps stay correct even when the entry is built
 * off-thread; the format is intentionally short (HH:mm:ss) because the
 * console UI shows date elsewhere via session-divider entries.
 */
data class LogEntry(
    val text: String,
    val type: LogType,
    val timestamp: String = SimpleDateFormat("HH:mm:ss").format(Date()),
    /**
     * Foreground-colour / bold runs recovered from the line's ANSI escapes, over
     * offsets into [text]. Empty for the common uncoloured line; the console
     * renders these on top of the severity colour.
     */
    val colors: List<AnsiRun> = emptyList(),
)
