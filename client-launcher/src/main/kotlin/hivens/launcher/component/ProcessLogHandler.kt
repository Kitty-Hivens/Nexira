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
                try {
                    reader.lineSequence().forEach { raw ->
                        // The raw line keeps its ANSI so the console can turn the
                        // game's `%style`/`%highlight` colours into styled spans
                        // (Cleanroom's TerminalConsole appender colours stdout even
                        // when it is a pipe). Classification and the on-disk game.log
                        // use the stripped text -- the file stays plain and the level
                        // regexes match the words, not the escapes.
                        val clean = stripAnsi(raw)
                        val finalType = classify(clean, type)
                        onLog(raw, finalType)

                        when (finalType) {
                            LauncherLogType.ERROR -> gameLog.error(clean)
                            LauncherLogType.WARN  -> gameLog.warn(clean)
                            else                  -> gameLog.info(clean)
                        }
                    }
                } catch (_: Exception) {
                    // Ignore EOF when terminating the process
                }
            }
        }
    }

    companion object {
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
