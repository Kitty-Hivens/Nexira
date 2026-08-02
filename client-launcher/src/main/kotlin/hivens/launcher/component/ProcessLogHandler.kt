package hivens.launcher.component

import hivens.core.data.LauncherLogType
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Component for asynchronous interception of I/O streams (IO Pipe).
 *
 * Provides redirection of `STDOUT` and `STDERR` of the child process
 * into a GUI callback function without blocking the main thread.
 *
 * Lines are also pushed into the `hivens.launcher.game` SLF4J logger so
 * Pulse routes them to `game.log` on disk in parallel with the in-app
 * `ConsoleWindow` -- useful for crash forensics when the launcher dies
 * before the user has a chance to hit "Save to file".
 */
internal class ProcessLogHandler {

    private val gameLog = LoggerFactory.getLogger("hivens.launcher.game")

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
        thread(isDaemon = true) {
            // `.use { }` guarantees the underlying stream handle is released
            // when the loop exits (EOF, exception, or process kill). Without
            // it the reader stayed referenced until GC.
            BufferedReader(InputStreamReader(stream)).use { reader ->
                val assembler = LineAssembler(MAX_LINE) { raw -> emit(raw, type, onLog) }
                val buf = CharArray(READ_CHUNK)
                try {
                    // Read raw chunks and split into lines ourselves rather than
                    // BufferedReader.readLine(): a game whose stdout has no newlines
                    // (a console layout whose `%n` sits inside an unresolved pattern
                    // converter never emits one) would otherwise buffer the entire
                    // run into a single line, delivered at EOF -- no live output, and
                    // one monster line to render. The assembler falls back to the
                    // record header and then to a length cap, so such a stream still
                    // arrives live, per record, and stays renderable.
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        assembler.feed(buf, n)
                    }
                    assembler.finish()
                } catch (_: Exception) {
                    // Ignore EOF when terminating the process
                }
            }
        }
    }

    private fun emit(raw: String, type: LauncherLogType, onLog: (String, LauncherLogType) -> Unit) {
        // The raw line keeps its ANSI so the console can turn the game's
        // `%style`/`%highlight` colours into styled spans. Classification and the
        // on-disk game.log use the stripped text -- the file stays plain and the
        // level regexes match the words, not the escapes.
        val clean = stripAnsi(raw)
        val finalType = classify(clean, type)
        onLog(raw, finalType)
        when (finalType) {
            LauncherLogType.ERROR -> gameLog.error(clean)
            LauncherLogType.WARN  -> gameLog.warn(clean)
            else                  -> gameLog.info(clean)
        }
    }

    companion object {
        /** Read buffer size for draining a child stream. */
        private const val READ_CHUNK = 8192

        /**
         * Hard cap on a single emitted line. Real game log lines are far shorter;
         * the cap only fires on a stream with no newlines (a broken log config),
         * chopping it into renderable pieces instead of one giant line.
         */
        internal const val MAX_LINE = 8192

        /**
         * ANSI control sequences a child may write to stdout/stderr: the CSI
         * form `ESC [ <params> <intermediates> <final>` (SGR colour `...m`,
         * cursor moves, erases) and the OSC form `ESC ] ... (BEL | ESC \)`
         * (window titles). Stripped so terminal-oriented game log output stays
         * readable in a non-terminal console pane.
         */
        internal val ANSI_ESCAPE_RE: Regex =
            Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007]*(?:\u0007|\u001B\\\\))")

        /** Removes ANSI escape sequences, leaving the plain text. */
        internal fun stripAnsi(text: String): String =
            if (text.indexOf('\u001B') < 0) text else ANSI_ESCAPE_RE.replace(text, "")

        /**
         * Matches a log4j/logback-style level marker that some logging
         * framework -- Forge/NeoForge's log4j config, vanilla MC's slf4j
         * setup, modlauncher -- emits near the start of each line:
         *
         *   `[19:42:13] [main/INFO]: Initializing...`
         *   `[Server thread/WARN]: Something deprecated`
         *   `[FATAL]: Couldn't load X`
         *
         * The pre-bracket portion (optional thread / source / timestamp) is
         * ignored; only the trailing `LEVEL]` is captured. When a line has no
         * such prefix (stack-trace bodies, plain stdout) we fall back to a
         * word-boundary substring scan below.
         */
        internal val LEVEL_PREFIX_RE: Regex =
            Regex("""\[(?:[^]]*?[/ ])?(INFO|WARN|WARNING|ERROR|FATAL|SEVERE|DEBUG|TRACE)]""")

        /**
         * Fallback for log lines without a structured prefix -- typically
         * stack traces (`at java.lang...`), JVM diagnostic spam, or
         * unframed game-side prints. `\b` anchors fix the prior bug where
         * `text.contains("WARN")` matched "no warnings" / `text.contains("ERROR")`
         * matched "errorless".
         *
         * The `\w*Exception\b` branch matches any throwable class name
         * ending in `Exception` (e.g. `NullPointerException`, `IOException`),
         * because plain `\bException\b` won't fire inside CamelCase --
         * `r` and `E` are both word chars so no boundary sits between
         * them. The suffix anchor stays restrictive enough that
         * `ExceptionLess`-style text doesn't false-positive (no word
         * boundary after the trailing `n`).
         */
        internal val FALLBACK_ERROR_RE: Regex =
            Regex("""\b(?:ERROR|FATAL|SEVERE|Caused\s+by)\b|\w*Exception\b""", RegexOption.IGNORE_CASE)

        internal val FALLBACK_WARN_RE: Regex =
            Regex("""\b(?:WARN|WARNING|Deprecated)\b""", RegexOption.IGNORE_CASE)

        /**
         * Stream type ERROR (stderr) always wins as ERROR -- log4j configs
         * occasionally route INFO lines to stderr by mistake, but treating
         * everything on stderr as at-least-ERROR matches what the user
         * actually wants surfaced in the console pane.
         *
         * Otherwise: structured prefix wins; then word-boundary substring;
         * default INFO.
         */
        internal fun classify(text: String, streamType: LauncherLogType): LauncherLogType {
            if (streamType == LauncherLogType.ERROR) return LauncherLogType.ERROR

            val prefixed = LEVEL_PREFIX_RE.find(text)?.groupValues?.get(1)?.let { lvl ->
                when (lvl.uppercase()) {
                    "FATAL", "SEVERE", "ERROR" -> LauncherLogType.ERROR
                    "WARN", "WARNING"          -> LauncherLogType.WARN
                    else                        -> LauncherLogType.INFO
                }
            }
            if (prefixed != null) return prefixed

            return when {
                FALLBACK_ERROR_RE.containsMatchIn(text) -> LauncherLogType.ERROR
                FALLBACK_WARN_RE.containsMatchIn(text)  -> LauncherLogType.WARN
                else                                    -> LauncherLogType.INFO
            }
        }
    }
}

/**
 * Splits a stream of character chunks into lines. `\n` is the primary boundary
 * (a trailing `\r` of a `\r\n` pair is dropped). A stream that stops producing
 * newlines falls back to splitting on the log-record header `[HH:MM:SS]`, and
 * finally on a [maxLen] cap, so it still yields live, bounded lines instead of
 * one buffer-until-EOF monster. [onLine] fires per completed line; [finish]
 * flushes any trailing partial line.
 *
 * The record fallback latches only after [starveThreshold] characters arrive
 * with no newline, and unlatches on the next one. A healthy stream therefore
 * never splits on a bracketed timestamp that happens to sit inside a chat
 * message; a stream whose layout swallowed its `%n` splits per record from the
 * first backlog onward.
 */
internal class LineAssembler(
    private val maxLen: Int,
    private val starveThreshold: Int = maxLen / 4,
    private val onLine: (String) -> Unit,
) {

    private val sb = StringBuilder()
    private var starved = false

    fun feed(chunk: CharArray, len: Int) {
        for (i in 0 until len) {
            val c = chunk[i]
            if (c == '\n') {
                flush()
                starved = false
                continue
            }
            sb.append(c)
            if (!starved && sb.length >= starveThreshold) {
                starved = true
                splitRecords()
            } else if (starved && c == ']') {
                splitRecords()
            }
            if (sb.length >= maxLen) flushCapped()
        }
    }

    fun finish() {
        if (sb.isNotEmpty()) flush()
    }

    private fun flush() {
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
        onLine(sb.toString())
        sb.setLength(0)
    }

    // Emits every complete record ahead of the last header in the buffer. The
    // trailing record stays buffered -- more of it may still be coming.
    private fun splitRecords() {
        var cut = nextRecordStart()
        while (cut > 0) {
            onLine(sb.substring(0, cut))
            sb.delete(0, cut)
            cut = nextRecordStart()
        }
    }

    // Offset of the next record header past index 0, colour prefix included, or
    // -1. A header at offset 0 (or one whose colour prefix reaches back to it)
    // is the buffer's own start, not a boundary.
    private fun nextRecordStart(): Int {
        var p = 1
        while (p + HEADER_LEN <= sb.length) {
            if (isHeaderAt(p)) {
                val cut = backOverAnsi(p)
                if (cut > 0) return cut
                p += HEADER_LEN
            } else {
                p++
            }
        }
        return -1
    }

    // `[HH:MM:SS]` -- the one part of a log record every layout in the wild keeps
    // intact, whatever it does to the level, the logger name or the message.
    private fun isHeaderAt(i: Int): Boolean =
        sb[i] == '[' && sb[i + 3] == ':' && sb[i + 6] == ':' && sb[i + 9] == ']' &&
            sb[i + 1].isDigit() && sb[i + 2].isDigit() &&
            sb[i + 4].isDigit() && sb[i + 5].isDigit() &&
            sb[i + 7].isDigit() && sb[i + 8].isDigit()

    // Walks back over the SGR sequences that colour the header so the cut lands
    // before them; otherwise every record would open with the previous record's
    // dangling escape.
    private fun backOverAnsi(start: Int): Int {
        var i = start
        while (i >= 2) {
            val esc = lastEscapeBefore(i)
            if (esc < 0 || esc + 1 >= i || sb[esc + 1] != '[') break
            var j = esc + 2
            while (j < i && sb[j] in '0'..'?') j++
            if (j >= i || sb[j] != 'm' || j + 1 != i) break
            i = esc
        }
        return i
    }

    // Cap flush: retain a half-written escape so the cut never lands mid-sequence.
    // Splitting there would strand the tail (`93m`) at the head of the next line,
    // where -- no ESC left to mark it -- it renders as literal text.
    private fun flushCapped() {
        val keep = incompleteEscapeStart()
        if (keep <= 0) {
            onLine(sb.toString())
            sb.setLength(0)
        } else {
            onLine(sb.substring(0, keep))
            sb.delete(0, keep)
        }
    }

    private fun lastEscapeBefore(end: Int): Int {
        for (i in end - 1 downTo 0) if (sb[i] == ESC) return i
        return -1
    }

    // Offset of a trailing escape sequence that has not reached its final byte,
    // or -1 when the buffer ends on a complete one.
    private fun incompleteEscapeStart(): Int {
        val esc = lastEscapeBefore(sb.length)
        if (esc < 0) return -1
        if (esc == sb.length - 1) return esc
        return when (sb[esc + 1]) {
            '[' -> if ((esc + 2 until sb.length).any { sb[it] in '@'..'~' }) -1 else esc
            ']' -> if ((esc + 2 until sb.length).any { sb[it] == BEL }) -1 else esc
            else -> -1
        }
    }

    private companion object {
        const val ESC = '\u001B'
        const val BEL = '\u0007'
        /** Length of `[HH:MM:SS]`. */
        const val HEADER_LEN = 10
    }
}
