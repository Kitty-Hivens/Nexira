package hivens.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.logging.Redactor
import hivens.launcher.platform.PlatformPaths
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date

private val log = LoggerFactory.getLogger("GameConsoleService")

/**
 * Holds the live in-memory console buffer + mirrors each entry to a
 * per-session `game-output-*.log` file. The in-memory buffer is a
 * sliding window: only the latest [maxLines] entries are kept; older
 * entries drop off the front but remain on disk. UI can ask for
 * [loadHistoryBefore] to page the older entries back into the window
 * (Phase 8.5 of the console rework -- see
 * [[project_console_sliding_window]]).
 *
 * Why memory-bounded:
 *
 * The AnnotatedString rebuild in `ConsoleWindow.buildConsoleAnnotated`
 * walks every entry in the window on every recomposition; if the
 * buffer grew unbounded, a long modded session would blow up the
 * rebuild cost. The disk file already exists per session (one line per
 * append, redacted at write time so safe to read back) and is the
 * obvious place to park history.
 *
 * Constructor-injected [paths] rather than a static
 * `PlatformPaths.system()` call so the singleton honors a mid-session
 * data-dir migration (`DataDirMover`) -- otherwise the in-memory writer
 * would keep landing lines in the old directory while everything else
 * writes to the new one.
 *
 * One instance is registered as a Koin singleton in the UI module and
 * shared between composables (`AppLayout`, `ConsoleWindow`, the
 * launcher controller). The snapshot-state list and `mutableStateOf`
 * flag survive recomposition naturally because Compose tracks the same
 * instance everywhere.
 */
class GameConsoleService(
    private val paths: PlatformPaths,
) {
    val logs = mutableStateListOf<LogEntry>()

    var shouldShowConsole by mutableStateOf(false)

    /**
     * Command sink: set by the [LaunchDriver] when a game process
     * spawns, cleared on error / clean exit. UI calls [sendCommand]
     * which routes through the sink to the process stdin. The state
     * field is observable so the input row can show / hide itself
     * without polling.
     *
     * Backed by mutableStateOf so the canSendCommands flag is a
     * regular Compose state read; the actual sink is the lambda the
     * driver stuffs in. Null = no game running, sendCommand is a
     * no-op.
     */
    private var _commandSink by mutableStateOf<((String) -> Unit)?>(null)
    val canSendCommands: Boolean get() = _commandSink != null

    /**
     * In-memory sliding-window cap. ConsoleSettings.maxInMemoryLines
     * drives this at runtime; the default is 5000 to give plenty of
     * scrollback in memory before the user has to page back from disk.
     * Trim runs on every append when size exceeds the cap.
     */
    var maxLines: Int = 5000

    /**
     * Count of entries that were dropped off the front of [logs] to
     * keep within [maxLines]. Equal to the number of older entries
     * available via [loadHistoryBefore]. UI binds the status footer to
     * this so the user sees "5000 in window / 12340 on disk".
     */
    private val _historyOffset = mutableIntStateOf(0)
    val historyOffset: Int get() = _historyOffset.intValue

    /**
     * Current session's on-disk file. Set by [startSession]; consulted
     * by [loadHistoryBefore] to know which file to page from. Null
     * before the first session start or after [clear].
     */
    private var sessionFile: File? = null

    /**
     * Pack/server id of the session currently feeding [logs]. The
     * PackDetail Logs tab reads this to decide whether the live buffer
     * belongs to the pack being viewed (-> show live) or some other
     * pack ran more recently (-> the tab loads its own pack's last
     * session file instead). Observable so the tab re-resolves when a
     * new session starts. Null before the first session.
     */
    var currentSessionPackId by mutableStateOf<String?>(null)
        private set

    private var sessionWriter: BufferedWriter? = null
    private val fileDateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

    /**
     * Serializes every [sessionWriter] / [sessionFile] touch. The
     * launcher's event collector funnels stdout + stderr through one
     * SharedFlow today but the writer historically supported direct
     * concurrent callers; keeping the lock means a future regression
     * does not corrupt the file's interleaving. Same lock guards
     * open / close in [startSession] / [clear] so a write isn't racing
     * the writer being swapped out, and [loadHistoryBefore] takes it
     * briefly to flush before reading.
     */
    private val writerLock = Any()

    private fun logsDir(): File =
        paths.logsDir.toFile().also { it.mkdirs() }

    fun startSession(packId: String? = null, packLabel: String? = null) {
        currentSessionPackId = packId
        synchronized(writerLock) {
            sessionWriter?.close()
            sessionWriter = null
            sessionFile = null
            _historyOffset.intValue = 0
            try {
                // Per-pack file name so the Logs tab can list / scope a
                // pack's own sessions. Unscoped sessions (null packId)
                // keep the legacy "game-output-<ts>.log" shape. The
                // sanitized id keeps arbitrary pack ids filesystem-safe.
                val stamp = fileDateFmt.format(Date())
                val fileName = if (packId.isNullOrBlank()) {
                    "game-output-$stamp.log"
                } else {
                    "game-output-${sanitizeId(packId)}-$stamp.log"
                }
                val file = File(logsDir(), fileName)
                sessionFile = file
                sessionWriter = BufferedWriter(FileWriter(file, true))
            } catch (e: Exception) {
                log.warn("Could not open per-session game-output log; in-memory console still works", e)
            }
        }

        // The divider goes into BOTH the in-memory buffer AND the file
        // so history reload reconstructs the session marker on the same
        // line index as the live view.
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        val entry = LogEntry("--------- Session started $time ---------", LogType.DIVIDER, time)
        logs.add(entry)
        synchronized(writerLock) {
            try {
                sessionWriter?.apply {
                    write(entry.text)
                    newLine()
                    flush()
                }
            } catch (e: Exception) {
                log.debug("Failed to mirror session-start divider to file", e)
            }
        }
    }

    fun append(text: String, type: LogType = LogType.INFO) {
        // Redact at append time: the in-memory buffer (which feeds
        // ConsoleWindow, the auto-save file, and `Save to file`
        // exports) NEVER carries raw accessTokens / passwords / UUIDs.
        // Means a screenshot of the console or a Ctrl+C copy of a log
        // line is safe to share for support.
        val entry = LogEntry(Redactor.redact(text), type)
        logs.add(entry)
        if (logs.size > maxLines) {
            logs.removeAt(0)
            _historyOffset.intValue += 1
        }

        synchronized(writerLock) {
            try {
                sessionWriter?.apply {
                    write(formatFileLine(entry))
                    newLine()
                    flush()
                }
            } catch (e: Exception) {
                log.debug("Failed to mirror console entry to per-session file", e)
            }
        }
    }

    /**
     * Read at most [count] entries from the current session's log file,
     * positioned immediately before the sliding-window start. Returns
     * them in chronological order (oldest first) so the caller can
     * prepend to [logs]. Decrements [historyOffset] by the count
     * actually returned. Returns an empty list when there is no
     * remaining history, no session file yet, or the file read fails.
     *
     * Flush before read: the writer normally flushes on every append,
     * but the lock guard against [clear] races also ensures the file
     * is in a consistent state by the time the reader opens it.
     */
    fun loadHistoryBefore(count: Int): List<LogEntry> {
        if (count <= 0) return emptyList()
        val current = _historyOffset.intValue
        if (current <= 0) return emptyList()
        val file = synchronized(writerLock) {
            sessionWriter?.flush()
            sessionFile
        } ?: return emptyList()
        if (!file.exists()) return emptyList()

        val take = count.coerceAtMost(current)
        val skip = current - take

        val out = ArrayList<LogEntry>(take)
        try {
            file.bufferedReader().use { reader ->
                // Skip ahead to the window of interest. The disk file
                // index = entry index since startSession (dividers and
                // all), so historyOffset N corresponds to file lines
                // [0..N) being the dropped tail.
                var skipped = 0
                while (skipped < skip) {
                    if (reader.readLine() == null) break
                    skipped++
                }
                var taken = 0
                while (taken < take) {
                    val raw = reader.readLine() ?: break
                    out.add(parseFileLine(raw))
                    taken++
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to load history for sliding window", e)
            return emptyList()
        }

        if (out.isNotEmpty()) {
            _historyOffset.intValue -= out.size
        }
        return out
    }

    /**
     * Serialise a [LogEntry] to its on-disk line. INFO stays
     * `[HH:MM:SS] text` (the common case -- keeps files clean and
     * backward-compatible with pre-8.6 logs). WARN / ERROR carry a
     * marker after the timestamp so [parseFileLine] can restore the
     * severity colour on reload: `[HH:MM:SS] [WARN] text`. The marker
     * sits after our timestamp, so a real game line's own
     * `[Server thread/WARN]` (which appears later in the text) never
     * collides with it. Dividers write their text verbatim.
     */
    private fun formatFileLine(entry: LogEntry): String = when (entry.type) {
        LogType.DIVIDER -> entry.text
        LogType.INFO    -> "[${entry.timestamp}] ${entry.text}"
        LogType.WARN    -> "[${entry.timestamp}] $MARKER_WARN ${entry.text}"
        LogType.ERROR   -> "[${entry.timestamp}] $MARKER_ERROR ${entry.text}"
    }

    /**
     * Best-effort parse of a single file line back into a LogEntry.
     * Inverse of [formatFileLine]. A `[WARN]` / `[ERROR]` marker
     * immediately after the timestamp restores the severity; absent a
     * marker the line is INFO (also covers pre-8.6 files, which carried
     * no severity). Dividers are detected by the `---` fence.
     */
    private fun parseFileLine(line: String): LogEntry {
        if (line.startsWith("---") && line.endsWith("---")) {
            return LogEntry(line, LogType.DIVIDER, "")
        }
        if (line.length >= 11 && line[0] == '[' && line[9] == ']' && line[10] == ' ') {
            val ts = line.substring(1, 9)
            var rest = line.substring(11)
            val type = when {
                rest.startsWith("$MARKER_WARN ")  -> { rest = rest.removePrefix("$MARKER_WARN ");  LogType.WARN }
                rest.startsWith("$MARKER_ERROR ") -> { rest = rest.removePrefix("$MARKER_ERROR "); LogType.ERROR }
                else                              -> LogType.INFO
            }
            return LogEntry(rest, type, ts)
        }
        return LogEntry(line, LogType.INFO, "")
    }

    fun saveToFile(): File? {
        return try {
            val fileName = "console-export-${fileDateFmt.format(Date())}.log"
            val file = File(logsDir(), fileName)
            file.bufferedWriter().use { writer ->
                logs.forEach { entry ->
                    writer.write(formatFileLine(entry))
                    writer.newLine()
                }
            }
            file
        } catch (_: Exception) { null }
    }

    fun clear() {
        logs.clear()
        synchronized(writerLock) {
            sessionWriter?.close()
            sessionWriter = null
            sessionFile = null
            _historyOffset.intValue = 0
        }
    }

    fun show() { shouldShowConsole = true }
    fun hide() { shouldShowConsole = false }

    /**
     * Attach a stdin writer for the currently running game process.
     * Called by [LaunchDriver] on the [LaunchState.GameRunning]
     * transition. Subsequent [sendCommand] calls route through this
     * lambda. The driver replaces the sink on each launch, so the
     * UI never holds a stale reference across game restarts.
     */
    fun attachCommandSink(sink: (String) -> Unit) {
        _commandSink = sink
    }

    fun detachCommandSink() {
        _commandSink = null
    }

    /**
     * Best-effort write to the game process's stdin. Newline-suffixed
     * so each call lands as a complete command on the receiver side.
     * No-op when no process is running; the UI is expected to gate
     * the input affordance on [canSendCommands] but this is the
     * defensive backstop.
     */
    fun sendCommand(text: String) {
        val payload = text.trimEnd('\n', '\r')
        if (payload.isEmpty()) return
        _commandSink?.invoke(payload)
    }

    /**
     * One past session log file for a pack, surfaced by the Logs tab's
     * file picker. [label] is a human-friendly timestamp pulled from
     * the file name; [isLive] marks the file the current in-memory
     * session is still writing to.
     */
    data class SessionLogFile(
        val file: File,
        val label: String,
        val isLive: Boolean,
    )

    /**
     * List a pack's past session log files, newest first. Matches the
     * `game-output-<sanitized-packId>-<stamp>.log` naming from
     * [startSession]. The file currently being written (if its pack
     * matches) is flagged [SessionLogFile.isLive] so the picker can
     * label + default to it.
     */
    fun sessionFilesFor(packId: String): List<SessionLogFile> {
        val prefix = "game-output-${sanitizeId(packId)}-"
        val live = sessionFile
        return runCatching {
            logsDir().listFiles { f ->
                f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }?.map { f ->
                SessionLogFile(
                    file   = f,
                    label  = f.name.removePrefix(prefix).removeSuffix(".log"),
                    isLive = live != null && f.absolutePath == live.absolutePath,
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Read an entire session log file into LogEntry list (read-only
     * file-backed view for the Logs tab picker). Severity is restored
     * via [parseFileLine]. Large files are bounded by [limit] from the
     * tail so a multi-hundred-MB crash log doesn't blow up memory; the
     * default mirrors the in-memory window cap.
     */
    fun readSessionFile(file: File, limit: Int = maxLines): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val lines = file.bufferedReader().useLines { seq -> seq.toList() }
            val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
            tail.map { parseFileLine(it) }
        }.getOrDefault(emptyList())
    }

    companion object {
        // Severity markers written after the timestamp for non-INFO
        // lines (see formatFileLine / parseFileLine). Kept as literals
        // a human reading the raw log file recognises at a glance.
        private const val MARKER_WARN  = "[WARN]"
        private const val MARKER_ERROR = "[ERROR]"

        /** Filesystem-safe form of a pack/server id for log file names. */
        private fun sanitizeId(id: String): String =
            id.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
                .joinToString("")
    }
}
