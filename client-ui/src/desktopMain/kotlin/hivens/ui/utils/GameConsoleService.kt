package hivens.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.core.logging.Redactor
import hivens.launcher.platform.PlatformPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date

private val log = LoggerFactory.getLogger("GameConsoleService")

/**
 * Immutable, UI-facing view of the console buffer. Published off the UI thread
 * by the drainer and read on Main via `collectAsState`; [entries] is already a
 * fresh copy, so the consumer never copies a snapshot-state list on the hot path.
 */
data class ConsoleSnapshot(
    val entries: List<LogEntry> = emptyList(),
    val historyOffset: Int = 0,
)

/**
 * Holds the live console buffer + mirrors each entry to a per-session
 * `game-output-*.log` file. FULLY DECOUPLED from the UI thread:
 *
 * A modded Minecraft start floods stdout (5000+ lines in ~2s). The old design
 * mutated a `mutableStateListOf` AND `flush()`ed the file PER LINE on whatever
 * thread called [append] -- which is Main (the launcher event collector runs in
 * a LaunchedEffect). That dead-hung the UI during load and sometimes crashed
 * the shell. Now every mutating call is a non-blocking enqueue onto [channel];
 * a single drainer coroutine on [Dispatchers.IO] owns ALL mutable buffer + file
 * state (so no locks), batches file writes (one flush per drained burst, never
 * per line), and publishes a coalesced immutable [ConsoleSnapshot] at most once
 * per burst. The UI only ever observes that snapshot.
 *
 * The in-memory buffer is a sliding window: only the latest [maxLines] entries
 * are kept; older entries drop off the front but remain on disk and page back
 * via [loadHistoryBefore].
 *
 * Constructor-injected [paths] (not a static `PlatformPaths.system()`) so the
 * singleton honors a mid-session data-dir migration. One Koin singleton shared
 * across composables; the snapshot StateFlow + the low-rate Compose flags below
 * survive recomposition naturally.
 */
class GameConsoleService(
    private val paths: PlatformPaths,
) {
    private val _snapshot = MutableStateFlow(ConsoleSnapshot())

    /** Coalesced, off-thread view of the buffer. Consumed via `collectAsState`. */
    val snapshot: StateFlow<ConsoleSnapshot> = _snapshot.asStateFlow()

    var shouldShowConsole by mutableStateOf(false)

    /**
     * Command sink: set by the [hivens.ui.notifications.drivers.LaunchDriver]
     * when a game process spawns, cleared on error / clean exit. Observable so
     * the input row shows / hides without polling. Null = no game running.
     */
    private var _commandSink by mutableStateOf<((String) -> Unit)?>(null)
    val canSendCommands: Boolean get() = _commandSink != null

    /**
     * In-launcher console commands (e.g. the dev `uidebug` toggle), matched before
     * a typed line is forwarded to the game. UI-thread only: registered once at
     * shell mount, read on submit -- no cross-thread access, so no synchronization.
     */
    private val localCommands = LinkedHashMap<String, () -> Unit>()
    val hasLocalCommands: Boolean get() = localCommands.isNotEmpty()

    /**
     * In-memory sliding-window cap. `ConsoleSettings.maxInMemoryLines` drives
     * this at runtime; read by the drainer thread on every trim, written from
     * the UI thread -- @Volatile for cross-thread visibility (a late-applied
     * change is harmless).
     */
    @Volatile
    var maxLines: Int = 5000

    /**
     * Monotonic session-start counter, bumped on every [startSession] (on the
     * caller thread, before the drainer opens the file) so the PackDetail Logs
     * tab can re-list reactively. Observable Compose state -- low rate, stays on
     * the UI side.
     */
    var sessionStartCount by mutableIntStateOf(0)
        private set

    // ── Drainer-owned state (touched ONLY on the drainer coroutine) ──────────
    private val buffer = ArrayDeque<LogEntry>()
    private val slotEntries = HashMap<String, LogEntry>()
    private var historyOffset = 0
    private var sessionFile: File? = null
    private var sessionWriter: BufferedWriter? = null
    private val fileDateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

    // Unlimited so the cheap producer (trySend on Main) never blocks or drops;
    // a flood backs up in the channel and the drainer batch-empties it.
    private val channel = Channel<Msg>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { drainLoop() }
    }

    private sealed interface Msg {
        data class Append(val text: String, val type: LogType) : Msg
        data class AppendOrUpdate(val slotId: String, val text: String, val type: LogType) : Msg
        data class StartSession(val packId: String?) : Msg
        data object Clear : Msg
        data class LoadHistory(val count: Int, val result: CompletableDeferred<List<LogEntry>>) : Msg
        data class Close(val done: CompletableDeferred<Unit>) : Msg
    }

    /**
     * Drain loop: block for one message, then batch-drain everything else queued
     * without suspending. Under a flood this collapses thousands of appends into
     * one flush + one snapshot publish; when idle it is one publish per line.
     */
    private suspend fun drainLoop() {
        for (first in channel) {
            apply(first)
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                apply(next)
            }
            flushWriter()
            publish()
        }
    }

    /**
     * Turns a raw game line into an entry: ANSI escapes become colour runs, the
     * stripped text is redacted, and the runs are kept only when redaction did
     * not move offsets (it rarely fires, so coloured lines stay coloured).
     */
    private fun entryOf(text: String, type: LogType): LogEntry {
        val parsed = parseAnsi(text)
        val redacted = Redactor.redact(parsed.text)
        val colors = if (redacted == parsed.text) parsed.runs else emptyList()
        return LogEntry(redacted, type, colors = colors)
    }

    private fun apply(msg: Msg) {
        when (msg) {
            is Msg.Append -> {
                val entry = entryOf(msg.text, msg.type)
                buffer.addLast(entry)
                trim()
                writeLine(entry)
            }
            is Msg.AppendOrUpdate -> {
                // TTY carriage-return semantics: overwrite the slot's line in
                // place so a flood of "Runtime 1/1342 ... 1342/1342" collapses
                // to one updating line. NOT mirrored to the file (launcher-
                // internal provisioning ticks; archiving every tick would bloat
                // the file and break the disk/line-index alignment).
                val entry = entryOf(msg.text, msg.type)
                val prev = slotEntries[msg.slotId]
                val idx = if (prev != null) buffer.indexOf(prev) else -1
                if (idx >= 0) {
                    buffer[idx] = entry
                } else {
                    buffer.addLast(entry)
                    trim()
                }
                slotEntries[msg.slotId] = entry
            }
            is Msg.StartSession -> openSession(msg.packId)
            is Msg.Clear -> {
                buffer.clear()
                slotEntries.clear()
                closeWriter()
                historyOffset = 0
            }
            is Msg.LoadHistory -> msg.result.complete(pageHistory(msg.count))
            is Msg.Close -> {
                closeWriter()
                msg.done.complete(Unit)
            }
        }
    }

    private fun trim() {
        while (buffer.size > maxLines) {
            buffer.removeFirst()
            historyOffset += 1
        }
    }

    private fun publish() {
        _snapshot.value = ConsoleSnapshot(buffer.toList(), historyOffset)
    }

    // ── Public enqueue surface (non-blocking, callable from any thread) ──────

    fun append(text: String, type: LogType = LogType.INFO) {
        channel.trySend(Msg.Append(text, type))
    }

    fun appendOrUpdate(slotId: String, text: String, type: LogType = LogType.INFO) {
        channel.trySend(Msg.AppendOrUpdate(slotId, text, type))
    }

    fun startSession(packId: String? = null, packLabel: String? = null) {
        // Bump on the caller thread (Main) so the observable Compose state write
        // stays off the drainer; the file open happens in the drainer.
        sessionStartCount += 1
        channel.trySend(Msg.StartSession(packId))
    }

    fun clear() {
        channel.trySend(Msg.Clear)
    }

    /**
     * Page the [count] entries immediately before the sliding-window start back
     * into the live buffer (scroll-up). Routed through the drainer so the buffer
     * + historyOffset stay single-owner; returns the loaded entries (oldest
     * first) for the caller's scroll-anchor math. Empty when there is no
     * remaining history / no session file / read failure.
     */
    suspend fun loadHistoryBefore(count: Int): List<LogEntry> {
        if (count <= 0) return emptyList()
        val result = CompletableDeferred<List<LogEntry>>()
        channel.trySend(Msg.LoadHistory(count, result))
        return result.await()
    }

    /**
     * Stop the drainer and let go of the session file. Routed through the channel
     * so the writer is closed by its owner thread, after every already-queued line
     * has been mirrored.
     *
     * The Koin singleton lives as long as the process, so nothing in the shell
     * calls this; it exists for callers that must release the handle while the
     * process keeps running -- a test tearing down its temp dir, or a data-dir
     * migration. Windows refuses to delete or move a file that is still open.
     * Idempotent: a second call finds the channel closed and returns.
     */
    suspend fun close() {
        val done = CompletableDeferred<Unit>()
        if (channel.trySend(Msg.Close(done)).isSuccess) done.await()
        channel.close()
        scope.cancel()
    }

    // ── Drainer-thread implementation ────────────────────────────────────────

    private fun openSession(packId: String?) {
        slotEntries.clear()
        closeWriter()
        historyOffset = 0
        try {
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
        // Session-start divider goes into BOTH buffer and file so a history
        // reload reconstructs the marker on the same line index as the live view.
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        val entry = LogEntry("--------- Session started $time ---------", LogType.DIVIDER, time)
        buffer.addLast(entry)
        writeLine(entry)
    }

    /** Read+prepend history; returns the loaded entries (oldest first). */
    private fun pageHistory(count: Int): List<LogEntry> {
        val current = historyOffset
        if (current <= 0) return emptyList()
        sessionWriter?.flush()
        val file = sessionFile ?: return emptyList()
        if (!file.exists()) return emptyList()

        val take = count.coerceAtMost(current)
        val skip = current - take
        val out = ArrayList<LogEntry>(take)
        try {
            file.bufferedReader().use { reader ->
                // Disk file index = entry index since startSession, so
                // historyOffset N means file lines [0..N) are the dropped tail.
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
            buffer.addAll(0, out)
            historyOffset -= out.size
        }
        return out
    }

    private fun writeLine(entry: LogEntry) {
        if (entry.type == LogType.DIVIDER && entry.text.startsWith("---")) {
            // dividers write verbatim
        }
        try {
            sessionWriter?.apply {
                write(formatFileLine(entry))
                newLine()
            }
        } catch (e: Exception) {
            log.debug("Failed to mirror console entry to per-session file", e)
        }
    }

    private fun flushWriter() {
        try {
            sessionWriter?.flush()
        } catch (e: Exception) {
            log.debug("Failed to flush per-session file", e)
        }
    }

    private fun closeWriter() {
        try {
            sessionWriter?.close()
        } catch (_: Exception) {
        }
        sessionWriter = null
        sessionFile = null
    }

    private fun logsDir(): File =
        paths.logsDir.toFile().also { it.mkdirs() }

    /**
     * Serialise a [LogEntry] to its on-disk line. INFO stays `[HH:MM:SS] text`;
     * WARN / ERROR carry a marker after the timestamp so [parseFileLine] can
     * restore the severity on reload. Dividers write verbatim.
     */
    private fun formatFileLine(entry: LogEntry): String = when (entry.type) {
        LogType.DIVIDER -> entry.text
        LogType.INFO    -> "[${entry.timestamp}] ${entry.text}"
        LogType.WARN    -> "[${entry.timestamp}] $MARKER_WARN ${entry.text}"
        LogType.ERROR   -> "[${entry.timestamp}] $MARKER_ERROR ${entry.text}"
    }

    /** Inverse of [formatFileLine]; absent a marker the line is INFO. */
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

    /** Export the live buffer (current snapshot). */
    fun saveToFile(): File? = exportEntries(snapshot.value.entries)

    /**
     * Write [entries] to a fresh `console-export-*.log` in the on-disk format.
     * The Logs tab passes whatever it currently displays, so exporting a past
     * session writes that session -- not whatever the live buffer holds.
     */
    fun exportEntries(entries: List<LogEntry>): File? {
        return try {
            val fileName = "console-export-${fileDateFmt.format(Date())}.log"
            val file = File(logsDir(), fileName)
            file.bufferedWriter().use { writer ->
                entries.forEach { entry ->
                    writer.write(formatFileLine(entry))
                    writer.newLine()
                }
            }
            file
        } catch (_: Exception) { null }
    }

    fun show() { shouldShowConsole = true }
    fun hide() { shouldShowConsole = false }

    fun attachCommandSink(sink: (String) -> Unit) { _commandSink = sink }
    fun detachCommandSink() { _commandSink = null }

    /** Best-effort write to the running game's stdin. No-op when none running. */
    fun sendCommand(text: String) {
        val payload = text.trimEnd('\n', '\r')
        if (payload.isEmpty()) return
        _commandSink?.invoke(payload)
    }

    fun registerLocalCommand(name: String, handler: () -> Unit) {
        localCommands[name.trim().lowercase()] = handler
    }

    /**
     * Route a typed console line: a registered in-launcher command runs locally and
     * returns true; anything else is forwarded to the game's stdin (a no-op when no
     * game is running).
     */
    fun submitConsoleInput(text: String): Boolean {
        val local = localCommands[text.trim().lowercase()]
        if (local != null) { local(); return true }
        sendCommand(text)
        return false
    }

    /**
     * The launcher's captured-session files for a pack, newest first. Matches
     * the `game-output-<sanitized-packId>-<stamp>.log` naming from
     * [startSession]. File-backed (off the live path).
     */
    fun capturedSessionFiles(packId: String): List<File> {
        val prefix = "game-output-${sanitizeId(packId)}-"
        return runCatching {
            logsDir().listFiles { f ->
                f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Read a log file into a LogEntry list for a read-only file-backed view.
     * Handles both our captured format and a game's own log; every line is run
     * through [Redactor] on read and the result is tail-bounded by [limit].
     */
    fun readLogFile(file: File, limit: Int = maxLines): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val lines = file.bufferedReader().useLines { seq -> seq.toList() }
            val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
            tail.map { parseDisplayLine(it) }
        }.getOrDefault(emptyList())
    }

    /** Redact + parse one external/captured log line for display. */
    private fun parseDisplayLine(raw: String): LogEntry {
        val redacted = Redactor.redact(raw)
        val base = parseFileLine(redacted)
        if (base.type != LogType.INFO) return base
        val mc = when {
            base.text.contains("/ERROR]") || base.text.contains("/FATAL]") -> LogType.ERROR
            base.text.contains("/WARN]")                                   -> LogType.WARN
            else                                                           -> LogType.INFO
        }
        return if (mc == base.type) base else base.copy(type = mc)
    }

    companion object {
        private const val MARKER_WARN  = "[WARN]"
        private const val MARKER_ERROR = "[ERROR]"

        /** Filesystem-safe form of a pack/server id for log file names. */
        private fun sanitizeId(id: String): String =
            id.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
                .joinToString("")
    }
}
