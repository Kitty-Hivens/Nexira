package hivens.ui.screens.console

import hivens.ui.screens.ERROR_MARKERS
import hivens.ui.screens.MAX_SEARCH_MATCHES
import hivens.ui.screens.SpanRole
import hivens.ui.screens.findAllSubstring
import hivens.ui.utils.FilterRule
import hivens.ui.utils.HighlightRule
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType

// One displayed line, ready to lay out on its own. This is the per-line successor
// to the old whole-buffer ConsoleDoc: the same filter + span logic, but the text
// and span offsets are LINE-LOCAL, so a line lays out independently and the canvas
// never concatenates the buffer into one giant string.
internal class LineSpan(
    val start: Int,
    val end: Int,
    val role: SpanRole,
    val colorHex: String? = null,
    val bold: Boolean = false,
)

internal class LineModel(
    val entry: LogEntry,
    val text: String,
    val spans: List<LineSpan>,
    val severity: LogType,
)

// A search hit, in the FILTERED line space, for F3/n navigation: the canvas
// scrolls to [line] and selects [start, end). Ordered top-to-bottom.
internal class LineMatch(val line: Int, val start: Int, val end: Int)

// The structural result of one filter pass: the displayed lines plus the counts
// the toolbar / footer show and the ordered match list. Palette-free -- colour is
// applied per line in the layout cache, so a theme change never re-runs this.
internal class LineModels(
    val lines: List<LineModel>,
    val matches: List<LineMatch>,
    val filteredCount: Int,
    val totalCount: Int,
    val warnCount: Int,
    val errorCount: Int,
    val searchCapped: Boolean = false,
) {
    companion object {
        val EMPTY = LineModels(emptyList(), emptyList(), 0, 0, 0, 0)
    }
}

/**
 * Filter [all] down to the displayed lines and build a [LineModel] for each. Mirrors
 * the old buildConsoleDoc pass exactly -- severity gate, search-as-filter narrowing,
 * user mute rules, dividers-always-pass, timestamp prefix, severity/highlight/marker/
 * search spans, warn/error counts, MAX_SEARCH_MATCHES cap -- minus the single-string
 * concatenation. Spans carry line-local offsets.
 */
internal fun buildLineModels(
    all: List<LogEntry>,
    filterInfo: Boolean,
    filterWarn: Boolean,
    filterError: Boolean,
    searchAsFilter: Boolean,
    rawQuery: String,
    regexMode: Boolean,
    regexCompiled: Regex?,
    showTimestamps: Boolean,
    highlightRules: List<HighlightRule> = emptyList(),
    filterRules: List<FilterRule> = emptyList(),
): LineModels {
    val activeFilters = filterRules.asSequence()
        .filter { it.enabled && it.pattern.isNotBlank() }
        .map { it to (if (it.regex) runCatching { Regex(it.pattern) }.getOrNull() else null) }
        .toList()
    val activeHighlights = highlightRules.asSequence()
        .filter { it.enabled && it.pattern.isNotBlank() }
        .map { it to (if (it.regex) runCatching { Regex(it.pattern) }.getOrNull() else null) }
        .toList()
    fun matchesFilter(text: String) = activeFilters.any { (r, rx) ->
        if (rx != null) rx.containsMatchIn(text) else text.contains(r.pattern, ignoreCase = true)
    }
    fun highlightFor(text: String): HighlightRule? = activeHighlights.firstOrNull { (r, rx) ->
        if (rx != null) rx.containsMatchIn(text) else text.contains(r.pattern, ignoreCase = true)
    }?.first

    var warnCount = 0
    var errorCount = 0
    val kept = ArrayList<LogEntry>(all.size)
    for (e in all) {
        when (e.type) {
            LogType.WARN  -> warnCount++
            LogType.ERROR -> errorCount++
            else          -> {}
        }
        val severityOk = when (e.type) {
            LogType.INFO    -> filterInfo
            LogType.WARN    -> filterWarn
            LogType.ERROR   -> filterError
            LogType.DIVIDER -> true
        }
        if (!severityOk) continue
        val queryOk = if (!searchAsFilter || rawQuery.isBlank() || e.type == LogType.DIVIDER) {
            true
        } else if (regexMode) {
            regexCompiled?.containsMatchIn(e.text) ?: false
        } else {
            e.text.contains(rawQuery, ignoreCase = true)
        }
        if (!queryOk) continue
        if (e.type != LogType.DIVIDER && matchesFilter(e.text)) continue
        kept.add(e)
    }

    val lines = ArrayList<LineModel>(kept.size)
    val matches = ArrayList<LineMatch>()
    var searchCapped = false

    for ((idx, e) in kept.withIndex()) {
        val spans = ArrayList<LineSpan>()
        val text: String
        if (e.type == LogType.DIVIDER) {
            text = e.text
            spans.add(LineSpan(0, text.length, SpanRole.Divider))
        } else {
            val role = when (e.type) {
                LogType.WARN  -> SpanRole.Warn
                LogType.ERROR -> SpanRole.Error
                else          -> SpanRole.Info
            }
            text = if (showTimestamps) "[${e.timestamp}] ${e.text}" else e.text
            spans.add(LineSpan(0, text.length, role))
            highlightFor(text)?.let { spans.add(LineSpan(0, text.length, role, it.colorHex, it.bold)) }
            if (e.type == LogType.ERROR || e.type == LogType.WARN) {
                ERROR_MARKERS.findAll(text).forEach { m ->
                    spans.add(LineSpan(m.range.first, m.range.last + 1, SpanRole.Marker))
                }
            }
        }

        if (rawQuery.isNotBlank() && matches.size < MAX_SEARCH_MATCHES) {
            val ranges = if (regexMode) {
                regexCompiled?.findAll(text)?.map { it.range }?.toList().orEmpty()
            } else {
                findAllSubstring(text, rawQuery)
            }
            for (r in ranges) {
                if (r.isEmpty()) continue
                val start = r.first
                val end = r.last + 1
                spans.add(LineSpan(start, end, SpanRole.Search))
                matches.add(LineMatch(idx, start, end))
                if (matches.size >= MAX_SEARCH_MATCHES) { searchCapped = true; break }
            }
        }

        lines.add(LineModel(e, text, spans, e.type))
    }

    return LineModels(
        lines = lines,
        matches = matches,
        filteredCount = kept.size,
        totalCount = all.size,
        warnCount = warnCount,
        errorCount = errorCount,
        searchCapped = searchCapped,
    )
}
