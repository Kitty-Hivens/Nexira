package hivens.launcher.component

import hivens.core.data.LauncherLogType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the line-level classifier in [ProcessLogHandler]. The pipe
 * threading itself is untestable without spawning a real process; the
 * classification function is pure and split out specifically to give
 * us a seam here.
 */
class ProcessLogHandlerTest {

    private fun classify(text: String, stream: LauncherLogType = LauncherLogType.INFO) =
        ProcessLogHandler.classify(text, stream)

    // ── stderr unconditional ────────────────────────────────────────────────

    @Test fun `stderr stream always classified as ERROR`() {
        assertEquals(LauncherLogType.ERROR, classify("Anything", LauncherLogType.ERROR))
        // Even an INFO-prefixed line on stderr stays ERROR -- some Forge
        // configs route too much through stderr; user wants to see it.
        assertEquals(
            LauncherLogType.ERROR,
            classify("[19:42:13] [main/INFO]: hello", LauncherLogType.ERROR),
        )
    }

    // ── structured log4j prefix wins ────────────────────────────────────────

    @Test fun `forge style INFO prefix produces INFO`() {
        assertEquals(LauncherLogType.INFO, classify("[19:42:13] [main/INFO]: Initializing FML..."))
    }

    @Test fun `forge style WARN prefix produces WARN`() {
        assertEquals(LauncherLogType.WARN, classify("[Server thread/WARN]: Deprecated config"))
    }

    @Test fun `forge style ERROR prefix produces ERROR`() {
        assertEquals(LauncherLogType.ERROR, classify("[modlauncher/ERROR] [foo]: Couldn't load X"))
    }

    @Test fun `bare bracket level produces matching level`() {
        assertEquals(LauncherLogType.ERROR, classify("[FATAL]: jvm exploded"))
        assertEquals(LauncherLogType.ERROR, classify("[SEVERE]: oops"))
    }

    // ── word-boundary fallback ──────────────────────────────────────────────

    @Test fun `stack trace line with Exception is ERROR`() {
        // No structured prefix, but the word "Exception" appears as a token.
        assertEquals(
            LauncherLogType.ERROR,
            classify("\tat java.lang.NullPointerException: oops"),
        )
    }

    @Test fun `caused by line is ERROR`() {
        assertEquals(LauncherLogType.ERROR, classify("Caused by: java.io.IOException"))
    }

    // ── false-positive regressions the old code triggered ──────────────────

    @Test fun `phrase no warnings is NOT classified as WARN`() {
        // Old code: text.contains("WARN") matched "warnings" -> wrong.
        assertEquals(LauncherLogType.INFO, classify("Build complete, no warnings"))
    }

    @Test fun `phrase errorless is NOT classified as ERROR`() {
        // Old code: text.contains("ERROR") matched "errorless" -> wrong.
        assertEquals(LauncherLogType.INFO, classify("Pass succeeded errorless"))
    }

    @Test fun `phrase swarming is NOT classified as WARN`() {
        // "WARM"/"WARN" substring trap.
        assertEquals(LauncherLogType.INFO, classify("Bees are swarming the entity tracker"))
    }

    // ── plain INFO baseline ─────────────────────────────────────────────────

    @Test fun `boring stdout line is INFO`() {
        assertEquals(LauncherLogType.INFO, classify("Done (12.345s)! For help, type help"))
    }

    @Test fun `empty line is INFO`() {
        assertEquals(LauncherLogType.INFO, classify(""))
    }

    // ── ANSI stripping ──────────────────────────────────────────────────────

    private val esc = '\u001B'

    @Test fun `strips SGR colour codes, keeps the text`() {
        // A Cleanroom-style coloured console line (TerminalConsole %style/%highlight).
        val raw = "$esc[93m[01:14:00]$esc[m $esc[36m[main|Foundation]$esc[m 1304"
        assertEquals("[01:14:00] [main|Foundation] 1304", ProcessLogHandler.stripAnsi(raw))
    }

    @Test fun `strips a bare reset and multi-param SGR`() {
        assertEquals("bold then normal", ProcessLogHandler.stripAnsi("$esc[1;31mbold$esc[0m then normal"))
    }

    @Test fun `strips an OSC window-title sequence terminated by BEL`() {
        assertEquals("after", ProcessLogHandler.stripAnsi("$esc]0;some title\u0007after"))
    }

    @Test fun `plain text without escapes is returned unchanged`() {
        val line = "[19:42:13] [main/INFO]: nothing to strip"
        assertEquals(line, ProcessLogHandler.stripAnsi(line))
    }

    @Test fun `classification runs on the stripped line, not the raw escapes`() {
        // The WARN level sits inside a coloured prefix; stripping first lets the
        // level regex see it.
        val raw = "$esc[33m[Server thread/WARN]$esc[m: deprecated"
        assertEquals(LauncherLogType.WARN, classify(ProcessLogHandler.stripAnsi(raw)))
    }

    // ── line assembly ───────────────────────────────────────────────────────

    private fun assemble(
        vararg chunks: String,
        maxLen: Int = 8192,
        starveThreshold: Int = maxLen / 4,
    ): List<String> {
        val out = ArrayList<String>()
        val a = LineAssembler(maxLen, starveThreshold) { out.add(it) }
        for (ch in chunks) a.feed(ch.toCharArray(), ch.length)
        a.finish()
        return out
    }

    @Test fun `assembler splits on newline`() {
        assertEquals(listOf("a", "b", "c"), assemble("a\nb\nc"))
    }

    @Test fun `assembler drops the trailing CR of a CRLF`() {
        assertEquals(listOf("x", "y"), assemble("x\r\ny"))
    }

    @Test fun `assembler joins a line split across chunks`() {
        assertEquals(listOf("hello", "world"), assemble("hel", "lo\nwor", "ld"))
    }

    @Test fun `assembler flushes a trailing partial line at finish`() {
        assertEquals(listOf("no newline"), assemble("no newline"))
    }

    @Test fun `assembler caps a newline-starved run into bounded pieces`() {
        // 20 chars, no newline, cap 8 -> 8 + 8 + 4.
        assertEquals(listOf("aaaaaaaa", "aaaaaaaa", "aaaa"), assemble("a".repeat(20), maxLen = 8))
    }

    @Test fun `assembler emits a blank line for a bare newline`() {
        assertEquals(listOf(""), assemble("\n"))
    }

    // ── record fallback for a newline-starved stream ────────────────────────

    @Test fun `newline starved stream splits per log record`() {
        val run = "[15:39:30] [main|Foundation] 1468" +
            "[15:39:31] [main|FML] 2024" +
            "[15:39:33] [main|Cleanroom] 4348"
        assertEquals(
            listOf(
                "[15:39:30] [main|Foundation] 1468",
                "[15:39:31] [main|FML] 2024",
                "[15:39:33] [main|Cleanroom] 4348",
            ),
            assemble(run, starveThreshold = 20),
        )
    }

    @Test fun `record split survives a boundary between chunks`() {
        assertEquals(
            listOf("[15:39:30] [main|FML] a", "[15:39:31] [main|FML] b"),
            assemble("[15:39:30] [main|F", "ML] a[15:39:31] [main|FML] b", starveThreshold = 20),
        )
    }

    @Test fun `healthy line is not split on a timestamp inside the message`() {
        // The threshold never trips, so a chat line quoting a clock stays whole.
        assertEquals(
            listOf("[15:39:30] [main/INFO]: <Steve> meet at [12:00:00] sharp"),
            assemble("[15:39:30] [main/INFO]: <Steve> meet at [12:00:00] sharp\n"),
        )
    }

    @Test fun `a newline unlatches the record fallback`() {
        val out = assemble("x".repeat(45), "\n", "[10:00:00] a [11:00:00] b\n", starveThreshold = 40)
        assertEquals(listOf("x".repeat(45), "[10:00:00] a [11:00:00] b"), out)
    }

    @Test fun `record split cuts before the colour prefix, not inside it`() {
        val run = "$esc[93m[15:39:30]$esc[m msg1 $esc[93m[15:39:31]$esc[m msg2"
        assertEquals(
            listOf("$esc[93m[15:39:30]$esc[m msg1 ", "$esc[93m[15:39:31]$esc[m msg2"),
            assemble(run, starveThreshold = 20),
        )
    }

    @Test fun `cap flush keeps a half written escape with its remainder`() {
        // Cutting at the cap mid-sequence stranded the tail ("93m") at the head of
        // the next line, where it rendered as literal text.
        assertEquals(
            listOf("a".repeat(10), "$esc[93m"),
            assemble("a".repeat(10) + "$esc[93m", maxLen = 12, starveThreshold = 10_000),
        )
    }
}
