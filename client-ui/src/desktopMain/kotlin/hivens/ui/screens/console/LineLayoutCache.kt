package hivens.ui.screens.console

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import hivens.ui.screens.ConsolePalette
import hivens.ui.screens.spanStyleFor
import hivens.ui.theme.CustomTheme
import hivens.ui.utils.LogEntry

// The colour + geometry a line lays out under. A change to ANY field means the
// cached layouts are stale, so the canvas bumps the cache generation (invalidate)
// when it changes -- palette on a theme tick, baseStyle on a font-size change,
// wrap/width on a resize or wrap toggle.
internal data class LineStyle(
    val palette: ConsolePalette,
    val baseStyle: TextStyle,
    val wrap: Boolean,
    val widthPx: Int,
)

/**
 * Lazy, bounded cache of one [TextLayoutResult] per line. A line is measured the
 * first time it enters the viewport and reused forever after -- this is what makes
 * append O(1) (only the new line measures) and scrolling layout-free (visible lines
 * are already measured). Keyed by the [LogEntry] itself: snapshots republish the
 * same entry instances, so an append leaves every existing entry's layout cached.
 *
 * [invalidate] drops everything on a style change (theme / font / wrap / width /
 * query) -- those change what a line renders as, so every layout is stale. The LRU
 * bound keeps memory flat: only ~a window's worth of layouts are retained; a line
 * scrolled far away is re-measured (~0.14 ms) when it returns.
 */
internal class LineLayoutCache(
    private val measurer: TextMeasurer,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private var capacity = capacity.coerceAtLeast(1)

    // accessOrder=true -> least-recently-used is evicted first.
    private val cache = object : LinkedHashMap<LogEntry, TextLayoutResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LogEntry, TextLayoutResult>): Boolean =
            size > this@LineLayoutCache.capacity
    }

    // Actual measure passes since construction -- diagnostics + test hook. A cache
    // hit does not increment it.
    var measuredCount: Long = 0
        private set

    fun invalidate() { cache.clear() }

    fun setCapacity(n: Int) {
        capacity = n.coerceAtLeast(1)
        while (cache.size > capacity) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
        }
    }

    fun layoutFor(line: LineModel, style: LineStyle): TextLayoutResult {
        cache[line.entry]?.let { return it }
        val result = measurer.measure(
            text = annotatedFor(line, style.palette),
            style = style.baseStyle,
            softWrap = style.wrap,
            maxLines = Int.MAX_VALUE,
            constraints = if (style.wrap && style.widthPx > 0) Constraints(maxWidth = style.widthPx) else Constraints(),
        )
        measuredCount++
        cache[line.entry] = result
        return result
    }

    private fun annotatedFor(line: LineModel, palette: ConsolePalette): AnnotatedString = buildAnnotatedString {
        append(line.text)
        val len = line.text.length
        for (sp in line.spans) {
            val start = sp.start.coerceIn(0, len)
            val end = sp.end.coerceIn(start, len)
            if (start == end) continue
            val style = if (sp.colorHex != null) {
                SpanStyle(
                    color = CustomTheme.parseHexColor(sp.colorHex),
                    fontWeight = if (sp.bold) FontWeight.Bold else null,
                )
            } else {
                spanStyleFor(sp.role, palette)
            }
            addStyle(style, start, end)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 400
    }
}
