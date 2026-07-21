package hivens.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Isolated console-render benchmark. NOT a correctness gate -- it quantifies WHERE
 * the cost of a growing buffer lands, to test the hypothesis that console slowness
 * is not the line count itself but re-doing whole-buffer work on every append.
 *
 * Three layers measured independently:
 *  1. buildConsoleDoc + styleDoc scaling  -- the off-thread rebuild (String + AnnotatedString).
 *  2. append-flood                        -- N single-line appends, each doing a FULL rebuild
 *                                            (what ConsoleContent does today) vs an incremental append.
 *  3. text layout via TextMeasurer        -- laying out the whole-buffer AnnotatedString (what the
 *                                            single non-virtualized Text does on the UI thread) vs
 *                                            laying out only a tail window (what virtualization buys).
 *
 * Numbers are written to console-bench.txt and echoed to stdout. Assertions are
 * loose (ordering, not absolute ms) so the bench does not flake on a busy runner.
 *
 * @Ignore: run on demand, not in the normal suite -- it lays out huge buffers and
 * takes seconds. Un-ignore to profile:
 *   ./gradlew :client-ui:desktopTest --tests "hivens.ui.screens.ConsolePerfBench"
 */
@Ignore("profiler / perf harness; run on demand")
class ConsolePerfBench {

    private val out = StringBuilder()
    private fun log(line: String) { out.appendLine(line); println(line) }

    private fun flush(section: String) {
        val f = File(System.getenv("CONSOLE_BENCH_OUT") ?: "/tmp/console-bench.txt")
        f.appendText("== $section ==\n$out\n")
        out.clear()
    }

    // A representative log line: mixed severity, ~90-140 chars, some with exception markers.
    private fun buffer(n: Int): List<LogEntry> = ArrayList<LogEntry>(n).apply {
        for (i in 0 until n) {
            val type = when (i % 20) {
                7        -> LogType.WARN
                13       -> LogType.ERROR
                else     -> LogType.INFO
            }
            val text = when (type) {
                LogType.ERROR -> "java.lang.NullPointerException at net.minecraft.client.Foo.bar(Foo.java:$i) Caused by: bad state token=$i"
                LogType.WARN  -> "[Render thread/WARN] mod 'somemod' requested a deprecated API path, entry number $i in this session"
                else          -> "[Render thread/INFO] loaded chunk ($i, ${i * 2}) with ${i % 512} block entities, tick=$i ok"
            }
            add(LogEntry(text, type, timestamp = "12:00:%02d".format(i % 60)))
        }
    }

    private val palette = ConsolePalette(
        textPrimary = Color(0xFFEEEEEE), textSecondary = Color(0xFFB0B0B0),
        severityInfo = Color(0xFFEEEEEE), severityWarn = Color(0xFFE0B341), severityError = Color(0xFFD8484A),
        divider = Color(0xFF444444), searchMatch = Color(0xFF212121), searchMatchBg = Color(0xFFFFEB3B),
    )

    private fun fullDoc(entries: List<LogEntry>, query: String = "") =
        buildConsoleDoc(
            all = entries, filterInfo = true, filterWarn = true, filterError = true,
            searchAsFilter = false, rawQuery = query, regexMode = false, regexCompiled = null,
            showTimestamps = true,
        )

    private fun styled(entries: List<LogEntry>, query: String = "") = styleDoc(fullDoc(entries, query), palette)

    private inline fun ms(reps: Int = 1, block: () -> Unit): Double {
        // warm-up
        repeat(2) { block() }
        val t0 = System.nanoTime()
        repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    private fun measurer() = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
    )
    private val baseStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

    // ── 1. Off-thread rebuild scaling ────────────────────────────────────────
    @Test
    fun rebuildScaling() {
        log("lines |  build+style ms |  chars |  spans")
        for (n in listOf(1_000, 5_000, 10_000, 25_000)) {
            val entries = buffer(n)
            var render: ConsoleRender? = null
            val t = ms(reps = 5) { render = styled(entries) }
            val r = render!!
            log("%5d | %14.2f | %6d | %6d".format(n, t, r.annotated.text.length, r.annotated.spanStyles.size))
        }
        flush("1. rebuild scaling (full buildConsoleDoc + styleDoc)")
    }

    // ── 2. Append flood: full rebuild per append vs incremental ───────────────
    @Test
    fun appendFloodFullRebuildVsIncremental() {
        val base = 10_000
        val appends = 200
        val entries = ArrayList(buffer(base))

        // What ConsoleContent does today: every snapshot rebuilds the whole doc+string.
        val fullRebuild = ms(reps = 1) {
            val work = ArrayList(entries)
            repeat(appends) { i ->
                work.add(LogEntry("[INFO] appended tail line $i", LogType.INFO, "12:00:00"))
                styled(work) // full O(n) rebuild + full AnnotatedString every time
            }
        }

        // Incremental ideal: keep the text as-is, append only the new line's work.
        val incremental = ms(reps = 1) {
            val sb = StringBuilder(styled(entries).annotated.text)
            repeat(appends) { i ->
                sb.append('\n').append("[INFO] appended tail line $i")
            }
        }

        log("base=$base lines, $appends single-line appends")
        log("full-rebuild-per-append : %10.2f ms total  (%.3f ms/append)".format(fullRebuild, fullRebuild / appends))
        log("incremental-append      : %10.2f ms total  (%.4f ms/append)".format(incremental, incremental / appends))
        log("waste factor            : %.0fx".format(fullRebuild / incremental.coerceAtLeast(0.001)))
        flush("2. append flood (200 appends onto a 10k buffer)")
        // The whole point: full rebuild dwarfs incremental. Loose bound to avoid flake.
        assertTrue(fullRebuild > incremental * 5, "expected full-rebuild-per-append to be far costlier than incremental")
    }

    // ── 4. Per-line layout cache (the proposed custom canvas) ─────────────────
    // Model: each LogEntry lays out to its OWN TextLayoutResult, once, and is cached.
    // append = lay out one new line; scroll = draw cached layouts, zero re-layout.
    // This is what a virtualized log canvas with a line cache costs.
    @Test
    fun perLineCacheModel() {
        val m = measurer()
        val n = 10_000
        val entries = buffer(n)
        // Build one AnnotatedString per line (its own spans), as the canvas would.
        fun lineAnnotated(e: LogEntry) = styled(listOf(e)).annotated

        val lineDocs = entries.map { lineAnnotated(it) }

        // One-time cost: lay out every line individually and cache the result. This is
        // spread across the whole session (one line laid out once, ever) -- shown as a
        // batch only to prove total work is comparable to a single whole-buffer layout,
        // not a regression.
        val cache = ArrayList<Any>(n)
        val oneTimeAll = msCold(reps = 1) {
            cache.clear()
            for (a in lineDocs) cache.add(m.measure(text = a, style = baseStyle, softWrap = false, constraints = Constraints()))
        }

        // Per-append cost in the cached model: lay out exactly ONE new line.
        val appendOne = msCold(reps = 50) { i ->
            val a = lineAnnotated(LogEntry("[INFO] fresh appended line $i with some tokens t=$i", LogType.INFO, "12:00:00"))
            m.measure(text = a, style = baseStyle, softWrap = false, constraints = Constraints())
        }

        // Scroll-frame cost: 50 visible lines, layouts already cached -> zero measure.
        // We just touch the cached results (index lookups) to show a scroll frame does
        // NO layout work; drawing cached TextLayoutResults is a draw-only pass.
        val visible = 50
        val scrollFrame = msCold(reps = 200) { i ->
            val startLine = (i * 7) % (n - visible)
            var acc = 0
            for (k in 0 until visible) acc = acc xor cache[startLine + k].hashCode()
            if (acc == Int.MIN_VALUE) error("unreachable")
        }

        log("model: one TextLayoutResult per line, cached")
        log("one-time layout of all %d lines : %10.2f ms  (amortized: once per line, ever)".format(n, oneTimeAll))
        log("append ONE line (cached model)   : %10.4f ms  (vs ~1700 ms whole-buffer relayout today)".format(appendOne))
        log("scroll frame (%d cached lines)    : %10.4f ms  (zero re-layout)".format(visible, scrollFrame))
        flush("4. per-line layout cache (proposed custom canvas)")
        assertTrue(appendOne < oneTimeAll / 50, "expected single-line append to be a tiny fraction of a full layout")
    }

    // ── 3. Text layout: whole buffer vs tail window ───────────────────────────
    // COLD measurement: every append in the app produces a brand-new AnnotatedString,
    // so TextMeasurer's layout cache always misses. We mirror that by feeding a UNIQUE
    // string on each timed rep (distinct trailing line) -- a warm/repeat measure would
    // hit the cache and report a fraction of the real per-append UI-thread cost.
    private fun msCold(reps: Int, build: (Int) -> Unit): Double {
        val t0 = System.nanoTime()
        for (i in 0 until reps) build(i)
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    @Test
    fun layoutWholeBufferVsWindow() {
        val m = measurer()
        val noWrap = Constraints()                 // softWrap-off single Text (wrap-off mode)
        val wrap   = Constraints(maxWidth = 900)   // BasicTextField at ~900px (wrap-on mode)

        log("lines | cold layout no-wrap ms | cold layout wrap@900 ms")
        for (n in listOf(1_000, 5_000, 10_000)) {
            val base = buffer(n)
            val tNo = msCold(reps = 6) { i ->
                val a = styled(base + LogEntry("[INFO] unique tail $i", LogType.INFO, "12:00:00")).annotated
                m.measure(text = a, style = baseStyle, softWrap = false, constraints = noWrap)
            }
            val tWr = msCold(reps = 6) { i ->
                val a = styled(base + LogEntry("[INFO] unique tail $i", LogType.INFO, "12:00:00")).annotated
                m.measure(text = a, style = baseStyle, softWrap = true, constraints = wrap)
            }
            log("%5d | %21.2f | %22.2f".format(n, tNo, tWr))
        }

        // Virtualization payoff: laying out only the visible tail (~200 lines) instead
        // of all 10k, each rep cold/unique.
        val base10k = buffer(10_000)
        val tail    = buffer(10_000).takeLast(200)
        val whole = msCold(reps = 6) { i ->
            val a = styled(base10k + LogEntry("[INFO] u$i", LogType.INFO, "12:00:00")).annotated
            m.measure(text = a, style = baseStyle, softWrap = false, constraints = noWrap)
        }
        val window = msCold(reps = 6) { i ->
            val a = styled(tail + LogEntry("[INFO] u$i", LogType.INFO, "12:00:00")).annotated
            m.measure(text = a, style = baseStyle, softWrap = false, constraints = noWrap)
        }
        log("cold layout whole 10k : %10.2f ms".format(whole))
        log("cold layout tail 200  : %10.2f ms".format(window))
        log("layout waste factor   : %.0fx".format(whole / window.coerceAtLeast(0.001)))
        flush("3. text layout COLD (TextMeasurer, unique input per rep)")
        assertTrue(whole > window * 5, "expected whole-buffer layout to dwarf a tail-window layout")
    }
}
