package hivens.ui.screens.console

import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType

/**
 * The console's non-visual decisions, out of the composable that used to hold
 * them inline.
 *
 * The heavy pass already lives outside composition -- `buildLineModels` runs on
 * a background dispatcher and is tested on its own. What was left inside were
 * the small rules nobody could reach: what a search query compiles to, when
 * older history is worth paging in, where a match jump lands, and what text a
 * copy action actually produces. Each is a couple of lines, and each was
 * wedged between a `FocusRequester` and a scroll offset, so none of them had a
 * test.
 *
 * Everything genuinely bound to Compose -- scrolling, focus, text layout,
 * putting a string on the clipboard -- stays where it is. Dragging that out
 * would buy nothing and cost the console its directness.
 */

/** Distance from the top, in px, at which older history is paged in. */
internal const val HISTORY_PAGE_IN_THRESHOLD_PX = 80f

/**
 * The regex a query compiles to, or null when the search is plain text or the
 * pattern is not valid yet.
 *
 * Null while typing an incomplete pattern is deliberate: the filter treats it
 * as matching nothing, so the user sees an empty buffer rather than an
 * exception mid-keystroke.
 */
internal fun compileSearch(query: String, regexMode: Boolean): Regex? {
    if (!regexMode || query.isBlank()) return null
    return runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
}

/**
 * Whether to page older entries in.
 *
 * A file-backed view has its whole tail loaded up front, so there is nothing
 * above it; a load already in flight must not be re-entered, or a fast scroll
 * to the top queues overlapping reads of the same batch.
 */
internal fun shouldPageHistory(
    isLive: Boolean,
    loading: Boolean,
    historyOffset: Int,
    scrollOffsetPx: Float,
    thresholdPx: Float = HISTORY_PAGE_IN_THRESHOLD_PX,
): Boolean = isLive && !loading && historyOffset > 0 && scrollOffsetPx <= thresholdPx

/**
 * Where the match cursor lands, wrapping at both ends. Returns -1 for an empty
 * match set, which every caller reads as "nothing to jump to".
 */
internal fun nextMatchIndex(current: Int, total: Int): Int =
    if (total <= 0) -1 else (current + 1).mod(total)

internal fun previousMatchIndex(current: Int, total: Int): Int =
    if (total <= 0) -1 else (current - 1).mod(total)

/**
 * Keeps the cursor inside a match set that shrank under it -- a filter change
 * can drop the matches the cursor was sitting on.
 */
internal fun clampMatchIndex(current: Int, total: Int): Int =
    if (total <= 0 || current >= total) 0 else current

/**
 * The whole buffer as text. Dividers keep their own line verbatim, since a
 * session boundary with a timestamp glued to it reads as a log line.
 */
internal fun copyAllText(entries: List<LogEntry>): String =
    entries.joinToString("\n") { entry ->
        if (entry.type == LogType.DIVIDER) entry.text else "[${entry.timestamp}] ${entry.text}"
    }

/**
 * The logical line under the caret, or null when there is nothing worth
 * copying.
 *
 * No caret yet -- a right-click before any left-click -- falls back to the
 * first line, so the menu action always does something rather than appearing
 * broken.
 */
internal fun copyLineText(lines: List<String>, caretLine: Int?): String? {
    val text = lines.getOrNull(caretLine ?: 0) ?: return null
    return text.takeIf { it.isNotBlank() }
}
