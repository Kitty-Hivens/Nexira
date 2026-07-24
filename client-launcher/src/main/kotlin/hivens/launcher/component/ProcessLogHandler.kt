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
                    // (Cleanroom's broken log config eats the trailing `%n` on the
                    // console appender) would otherwise buffer the entire run into
                    // one line, emitted only at EOF -- no live output, and one
                    // monster line the console pane chokes on. The assembler also
                    // breaks on a length cap, so such a stream still shows up live
                    // and stays renderable.
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
 * Splits a stream of character chunks into lines: on `\n` (dropping a trailing
 * `\r` of a `\r\n` pair) and on a [maxLen] cap so a newline-starved stream still
 * yields bounded, live lines instead of one buffer-until-EOF monster. [onLine]
 * fires per completed line; [finish] flushes any trailing partial line.
 */
internal class LineAssembler(private val maxLen: Int, private val onLine: (String) -> Unit) {

    private val sb = StringBuilder()

    fun feed(chunk: CharArray, len: Int) {
        for (i in 0 until len) {
            val c = chunk[i]
            if (c == '\n') {
                flush()
            } else {
                sb.append(c)
                if (sb.length >= maxLen) flush()
            }
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
}
