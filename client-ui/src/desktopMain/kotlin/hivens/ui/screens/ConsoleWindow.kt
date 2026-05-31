package hivens.ui.screens

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.StyleSpec
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.utils.ConsoleSettings
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// Severity-only highlight + the exception markers that survive Slice A.
// Class-name / number / null highlights were noisy and lied when log
// formats shifted; cut. User-extensible rule list arrives with Slice C.
private val ERROR_MARKERS = Regex("(Exception|Error|FATAL|SEVERE|Caused by:|\\bat )")
private val FONT_SIZES = listOf(11, 12, 14)

// ── Palette ──────────────────────────────────────────────────────────────────
// Theme-derived colors flow through CelestiaTheme.colors at every composable
// call site; this small record carries the subset that pure helpers (the
// AnnotatedString builder) consume off the composition. Only console-only
// tokens (the yellow search highlight, the orange pause accent) live as
// constants -- everything else maps to a CelestiaColors role and follows
// the user's theme + customization overrides.
private data class ConsolePalette(
    val textPrimary:    Color,
    val textSecondary:  Color,
    val severityInfo:   Color,
    val severityWarn:   Color,
    val severityError:  Color,
    val divider:        Color,
    val searchMatch:    Color,
    val searchMatchBg:  Color,
)

// Console-only accents that have no CelestiaColors counterpart. Yellow
// search-match background is universally legible on either light or dark
// surfaces; pause-accent uses warm orange to read as "intentional halt"
// rather than failure (criticalAccent would conflate with ERROR severity).
private val CONSOLE_SEARCH_MATCH_BG = Color(0xFFFFEB3B)
private val CONSOLE_SEARCH_MATCH_FG = Color(0xFF212121)
private val CONSOLE_PAUSE_ACCENT   = Color(0xFFFFA726)

// ── Match index for F3/n navigation ─────────────────────────────────────────
// `ranges` carries each match's [start, endExclusive) so regex hits with
// per-match variable length highlight the actual matched text instead of a
// zero-width caret. `lineSeverities` is the parallel per-entry record the
// severity gutter strip needs -- one entry per LogEntry, carrying the char
// range it occupies in `annotated` so the painter can ask layoutResult for
// the y-extent of each visual line that range spans.
private data class MatchIndex(
    val annotated:      AnnotatedString,
    val ranges:         List<IntRange>,
    val lineSeverities: List<LineSeverity>,
)

private data class LineSeverity(
    val startOffset: Int,
    val endOffset:   Int,
    val type:        LogType,
)

/**
 * Where [ConsoleContent] reads its entries from.
 *
 * [Live] -- the running GameConsoleService buffer: tailing append,
 * sliding-window history paging, command input, clear / save. The
 * standalone ConsoleWindow and the PackDetail Logs tab's "current
 * session" view use this.
 *
 * [FileBacked] -- a static, read-only snapshot parsed from a past
 * session log file (the Logs tab's file picker). No tailing, no
 * paging, no command input; search / filter / gutter / copy all still
 * work on the static list.
 */
internal sealed interface ConsoleSource {
    data object Live : ConsoleSource
    data class FileBacked(val entries: List<LogEntry>) : ConsoleSource
}

// ── Main composable ─────────────────────────────────────────────────────────

@Composable
fun ConsoleWindow(
    isDarkTheme: Boolean,
    onClose: () -> Unit,
    customTheme: CustomTheme? = null,
    style: StyleSpec = CelestiaStyle,
    settings: ConsoleSettings = ConsoleSettings(),
    onSettingsChange: (ConsoleSettings) -> Unit = {},
) {
    val title = LocalStrings.current.consoleTitle
    val windowState = rememberWindowState(width = 960.dp, height = 620.dp)

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        title          = title,
        alwaysOnTop    = false,
        undecorated    = false,
    ) {
        // CelestiaTheme handles both the Material colorScheme + the
        // launcher's CelestiaColors composition local; child composables
        // read CelestiaTheme.colors directly. Accent / role overrides
        // from LocalCustomization propagate in if the caller wrapped the
        // ConsoleWindow site in a CustomizationProvider; otherwise the
        // default settings yield the same palette as the main shell.
        CelestiaTheme(
            useDarkTheme = isDarkTheme,
            customTheme  = customTheme,
            style        = style,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = CelestiaTheme.colors.background) {
                ConsoleContent(settings = settings, onSettingsChange = onSettingsChange)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ConsoleContent(
    settings: ConsoleSettings,
    onSettingsChange: (ConsoleSettings) -> Unit,
    source: ConsoleSource = ConsoleSource.Live,
) {
    val isLive = source is ConsoleSource.Live
    val s = LocalStrings.current
    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val gameConsole: GameConsoleService = koinInject()
    val themeColors = CelestiaTheme.colors

    // Pure-function helpers (the AnnotatedString builder) consume a value-
    // type palette off the composition; build it once per theme change so
    // the builder stays @Composable-free.
    val palette = remember(themeColors) {
        ConsolePalette(
            textPrimary    = themeColors.textPrimary,
            textSecondary  = themeColors.textSecondary,
            severityInfo   = themeColors.textPrimary,
            severityWarn   = themeColors.warnAccent,
            severityError  = themeColors.criticalAccent,
            divider        = themeColors.outline,
            searchMatch    = CONSOLE_SEARCH_MATCH_FG,
            searchMatchBg  = CONSOLE_SEARCH_MATCH_BG,
        )
    }

    // ── State ──────────────────────────────────────────────────────────────
    // Settings-derived shorthands. Changes flow back through onSettingsChange
    // so the persistence file stays the source of truth; local var reads
    // stay terse for the toolbar / kbd handler / context menu sites.
    val fontSize        = settings.fontSize.coerceIn(ConsoleSettings.MIN_FONT_SIZE, ConsoleSettings.MAX_FONT_SIZE)
    val wrapText        = settings.wrapText
    val showGutter      = settings.showGutterStrip
    val showTimestamps  = settings.showTimestamps

    // Apply the sliding-window cap to the service eagerly so changes to
    // the setting take effect on the next append rather than waiting for
    // the next session start.
    LaunchedEffect(settings.maxInMemoryLines) {
        gameConsole.maxLines = settings.maxInMemoryLines.coerceIn(
            ConsoleSettings.MIN_IN_MEMORY_LINES,
            ConsoleSettings.MAX_IN_MEMORY_LINES,
        )
    }

    var searchQuery     by remember { mutableStateOf("") }
    var regexMode       by remember { mutableStateOf(false) }
    var filterInfo      by remember { mutableStateOf(true) }
    var filterWarn      by remember { mutableStateOf(true) }
    var filterError     by remember { mutableStateOf(true) }
    var searchOpen      by remember { mutableStateOf(false) }
    var searchHasFocus  by remember { mutableStateOf(false) }
    var pendingSearchFocus by remember { mutableStateOf(false) }
    var searchAsFilter  by remember { mutableStateOf(false) }
    var cmdInput        by remember { mutableStateOf("") }
    var cmdHasFocus     by remember { mutableStateOf(false) }
    val cmdHistory      = remember { mutableStateListOf<String>() }
    var cmdHistoryIdx   by remember { mutableIntStateOf(-1) }
    var currentMatch    by remember { mutableIntStateOf(0) }
    var copiedFlash     by remember { mutableStateOf(false) }
    var selection       by remember { mutableStateOf(TextRange.Zero) }
    var layoutResult    by remember { mutableStateOf<TextLayoutResult?>(null) }

    val scrollState     = rememberScrollState()
    val searchFocus     = remember { FocusRequester() }
    val logFocus        = remember { FocusRequester() }
    val density         = LocalDensity.current

    // ── Frame-bounded log coalescer ────────────────────────────────────────
    // Live source: modded MC startup floods 5k+ lines in ~2s. Rebuilding
    // the whole AnnotatedString per append would jank; snapshotFlow +
    // conflate + distinctUntilChanged keeps rebuilds to one per frame.
    // File source: entries are static, so the tick never advances and the
    // snapshot is the parsed file -- no coalescer needed.
    var logTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isLive) {
        if (!isLive) return@LaunchedEffect
        snapshotFlow { gameConsole.logs.size }
            .conflate()
            .distinctUntilChanged()
            .collect { logTick = it }
    }
    val logsCopy = when (source) {
        is ConsoleSource.Live       -> remember(logTick) { gameConsole.logs.toList() }
        is ConsoleSource.FileBacked -> source.entries
    }

    // ── Search query debounce ──────────────────────────────────────────────
    // Highlight + match-offset rebuild keys off the debounced value;
    // counts and the prompt text key off the raw one so the user sees
    // their typing immediately.
    var effectiveQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(120)
        effectiveQuery = searchQuery
    }

    // Compile regex once per debounced query change. Invalid pattern -> null
    // (filter falls back to "match nothing" so the user sees an empty buffer
    // rather than an exception while they are still typing).
    val searchRegex = remember(effectiveQuery, regexMode) {
        if (regexMode && effectiveQuery.isNotBlank()) {
            runCatching { Regex(effectiveQuery, RegexOption.IGNORE_CASE) }.getOrNull()
        } else null
    }

    // ── Filtered + annotated buffer ────────────────────────────────────────
    // Two-stage filter: severity gates first, then optional query-narrowing
    // when search-as-filter is on. The default (filter off) leaves search
    // purely a highlight + F3 navigation aid; turning the mode on collapses
    // the buffer to just lines containing the active query, the same shape
    // less-grep gives. Dividers are kept verbatim either way so session
    // boundaries stay visible.
    val filtered = remember(
        logsCopy, filterInfo, filterWarn, filterError,
        searchAsFilter, effectiveQuery, regexMode, searchRegex,
    ) {
        val severityOk: (LogEntry) -> Boolean = { entry ->
            when (entry.type) {
                LogType.INFO    -> filterInfo
                LogType.WARN    -> filterWarn
                LogType.ERROR   -> filterError
                LogType.DIVIDER -> true
            }
        }
        val queryOk: (LogEntry) -> Boolean = q@{ entry ->
            if (!searchAsFilter || effectiveQuery.isBlank()) return@q true
            if (entry.type == LogType.DIVIDER) return@q true
            val haystack = entry.text
            if (regexMode) {
                searchRegex?.containsMatchIn(haystack) ?: false
            } else {
                haystack.contains(effectiveQuery, ignoreCase = true)
            }
        }
        logsCopy.filter { severityOk(it) && queryOk(it) }
    }

    val warnCount  = remember(logsCopy) { logsCopy.count { it.type == LogType.WARN } }
    val errorCount = remember(logsCopy) { logsCopy.count { it.type == LogType.ERROR } }

    // Async rebuild on Dispatchers.Default: the prior synchronous
    // `remember(...) { buildConsoleAnnotated(...) }` blocked the UI
    // thread for ~30-50 ms on 5000-line buffers under spammed filter
    // toggles, producing the user-reported lag. produceState swaps the
    // value in once the background coroutine returns; in-flight builds
    // are cancelled when the input keys change, so rapid clicks
    // collapse to one rebuild for the final state.
    //
    // First-render fallback is an empty MatchIndex; the gap is
    // sub-frame on a cold open (~30 ms) and visually negligible against
    // the window's own paint.
    val matchIndex by produceState(
        initialValue = remember { MatchIndex(AnnotatedString(""), emptyList(), emptyList()) },
        filtered, effectiveQuery, regexMode, searchRegex, palette, showTimestamps,
    ) {
        value = withContext(Dispatchers.Default) {
            buildConsoleAnnotated(filtered, effectiveQuery, regexMode, searchRegex, palette, showTimestamps)
        }
    }

    // Clamp current match index when the match set shrinks past it.
    LaunchedEffect(matchIndex.ranges.size) {
        if (currentMatch >= matchIndex.ranges.size) currentMatch = 0
    }

    // Initial focus on the log area: BasicTextField is read-only but still
    // focusable, and onPreviewKeyEvent at the Column root needs SOME node in
    // its subtree to hold focus before chord events route through. Without
    // this, Ctrl+F / F3 / j / k / g / G are dead until the user clicks
    // inside the (visually empty) text field. runCatching swallows the
    // pre-composition exception in the rare race where the field has not
    // attached yet -- the next attempt via user click resolves it.
    LaunchedEffect(Unit) {
        runCatching { logFocus.requestFocus() }
    }

    // Deferred focus request for the search prompt. openSearch() raises a
    // flag because the FocusRequester target (BasicTextField inside the
    // search-prompt Row) does not exist on the first composition pass --
    // requesting focus before the node is attached throws. Once
    // searchOpen=true triggers the SearchPrompt composable, this effect
    // fires on the next frame with the target alive.
    LaunchedEffect(pendingSearchFocus, searchOpen) {
        if (pendingSearchFocus && searchOpen) {
            runCatching { searchFocus.requestFocus() }
            pendingSearchFocus = false
        }
    }

    // ── Sticky-bottom follow ───────────────────────────────────────────────
    // User scrolling up naturally pauses follow because isAtBottom flips
    // false. Scrolling back to the bottom (or pressing G) flips it true
    // again. No explicit follow flag needed.
    val isAtBottom by remember {
        derivedStateOf {
            scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue - 16
        }
    }
    LaunchedEffect(matchIndex.annotated) {
        if (isAtBottom) scrollState.scrollTo(scrollState.maxValue)
    }

    // ── Lazy history page-in (sliding window upper edge) ───────────────────
    // When the user scrolls within 80 px of the top AND the service has
    // entries dropped past the window, page the older entries back in.
    // The load is one-shot: a `loading` flag suppresses re-entry while the
    // batch is in flight, otherwise rapid scroll-to-top events would queue
    // overlapping reads. Loaded entries land at the start of logs.toList()
    // (the next snapshotFlow tick rebuilds the AnnotatedString), and we
    // shift scrollState by the approximate height of the loaded block so
    // the user's visual line stays put.
    var historyLoading by remember { mutableStateOf(false) }
    // File-backed views load the whole (tail-bounded) file up front, so
    // there is nothing to page; historyOffset is meaningful only for the
    // live sliding window.
    val historyOffset by remember {
        derivedStateOf { if (isLive) gameConsole.historyOffset else 0 }
    }
    LaunchedEffect(scrollState.value, historyOffset, isLive) {
        if (!isLive) return@LaunchedEffect
        if (historyLoading) return@LaunchedEffect
        if (historyOffset <= 0) return@LaunchedEffect
        if (scrollState.value > 80) return@LaunchedEffect
        historyLoading = true
        try {
            val loaded = gameConsole.loadHistoryBefore(count = 500)
            if (loaded.isNotEmpty()) {
                // SnapshotStateList.addAll(0, ...) prepends; the next
                // snapshotFlow{logs.size} emit rebuilds matchIndex via
                // logsCopy, so the AnnotatedString picks the new front
                // automatically. ScrollState shifts by an approximate
                // line height per loaded entry; not exact because line
                // height under wrap depends on glyph metrics, but the
                // miss is sub-100 px and unnoticeable in practice.
                gameConsole.logs.addAll(0, loaded)
                val approxLineHeightPx = with(density) { (fontSize * 1.4f).sp.toPx() }
                val shift = (loaded.size * approxLineHeightPx).toInt()
                scrollState.scrollTo((scrollState.value + shift).coerceAtMost(scrollState.maxValue))
            }
        } finally {
            historyLoading = false
        }
    }

    // ── TextFieldValue source of truth ─────────────────────────────────────
    // Text comes from `matchIndex.annotated` (system-controlled). Selection
    // is user-controlled and persists across rebuilds; the read-only field
    // only routes selection changes (drag-select, Ctrl+A, click-position)
    // through onValueChange.
    val textFieldValue = remember(matchIndex.annotated, selection) {
        TextFieldValue(annotatedString = matchIndex.annotated, selection = selection)
    }

    // ── Copy actions ───────────────────────────────────────────────────────
    fun copyAll() {
        val text = logsCopy.joinToString("\n") { e ->
            if (e.type == LogType.DIVIDER) e.text else "[${e.timestamp}] ${e.text}"
        }
        scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
        copiedFlash = true
        scope.launch {
            delay(900)
            copiedFlash = false
        }
    }

    // Copy the LOGICAL line under the current caret. Works off the
    // annotated text's '\n' boundaries directly, not the field's
    // TextLayoutResult -- the context-menu callback can fire before
    // layoutResult is populated (or after a buffer-rebuild swaps it
    // out), and the prior "layout ?: return" guard silently swallowed
    // the click + the toast that goes with it. One entry equals one
    // logical line in our builder, so '\n' bounds are authoritative.
    fun copyLine() {
        val annotated = matchIndex.annotated
        if (annotated.isEmpty()) return
        val text = annotated.text
        val pos = selection.start.coerceIn(0, text.length)
        val lineStart = if (pos == 0) 0
                        else text.lastIndexOf('\n', startIndex = pos - 1) + 1
        val rawEnd = text.indexOf('\n', pos)
        val lineEnd = if (rawEnd < 0) text.length else rawEnd
        if (lineStart > lineEnd) return
        val lineText = text.substring(lineStart, lineEnd)
        if (lineText.isBlank()) return
        scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(lineText))) }
        copiedFlash = true
        scope.launch {
            delay(900)
            copiedFlash = false
        }
    }

    // Copy the active selection. Collapsed selection (no drag) -> no-op;
    // the user can switch to copy-line for that case via the same menu.
    fun copySelection() {
        if (selection.collapsed) return
        val annotated = matchIndex.annotated
        val start = selection.start.coerceIn(0, annotated.length)
        val end   = selection.end.coerceIn(0, annotated.length)
        if (start >= end) return
        val text = annotated.substring(start, end)
        scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
        copiedFlash = true
        scope.launch {
            delay(900)
            copiedFlash = false
        }
    }

    // ── Match jumping ──────────────────────────────────────────────────────
    // The layout result may be from the PREVIOUS frame's BasicTextField measure
    // while matchIndex.ranges references char positions in the JUST-rebuilt
    // annotated string. Under flood, an offset can exceed the old layout's
    // text length -- MultiParagraph.getLineForOffset would throw. Guard on
    // text length first, then runCatching the layout call so a transient
    // out-of-range only loses one F3 press instead of crashing.
    fun scrollToMatch(idx: Int) {
        val layout = layoutResult ?: return
        if (idx !in matchIndex.ranges.indices) return
        val range = matchIndex.ranges[idx]
        val start = range.first
        val end   = range.last + 1
        if (start >= layout.layoutInput.text.length) return
        val line = runCatching { layout.getLineForOffset(start) }.getOrNull() ?: return
        val top = runCatching { layout.getLineTop(line).toInt() }.getOrNull() ?: return
        val target = (top - scrollState.viewportSize / 3).coerceAtLeast(0)
        scope.launch { scrollState.animateScrollTo(target) }
        // Selection mirrors the match span exactly -- in regex mode this
        // visualises the full matched text rather than collapsing to a
        // zero-width caret as the prior queryLength-based form did.
        val safeEnd = end.coerceAtMost(matchIndex.annotated.length)
        selection = TextRange(start, safeEnd)
    }

    fun jumpNext() {
        if (matchIndex.ranges.isEmpty()) return
        currentMatch = (currentMatch + 1) % matchIndex.ranges.size
        scrollToMatch(currentMatch)
    }

    fun jumpPrev() {
        if (matchIndex.ranges.isEmpty()) return
        currentMatch = if (currentMatch == 0) matchIndex.ranges.lastIndex
                       else currentMatch - 1
        scrollToMatch(currentMatch)
    }

    fun openSearch() {
        searchOpen = true
        // Compose can't focus a node before its first composition pass; defer
        // the focus request to the next frame via a side-effect flag.
        pendingSearchFocus = true
    }
    fun closeSearch() {
        searchOpen = false
        searchHasFocus = false
        // Returning focus to the log area (not clearing) keeps the root
        // onPreviewKeyEvent live so j/k/g/G/Ctrl+End/etc. continue to work
        // after dismissing the prompt. clearFocus() leaves no owner in the
        // window's focus tree -- chords go dead until the user clicks.
        runCatching { logFocus.requestFocus() }
    }

    // ── Puppet hooks ────────────────────────────────────────────────────────
    // Public contract: existing ids preserved verbatim so older e2e drivers
    // do not break. New ids for search-open / next-prev added on top.
    PuppetScreen("Console")
    PuppetToggle("console.filterInfo",  filterInfo)  { filterInfo = it }
    PuppetToggle("console.filterWarn",  filterWarn)  { filterWarn = it }
    PuppetToggle("console.filterError", filterError) { filterError = it }
    PuppetToggle("console.wrap",        wrapText)    { onSettingsChange(settings.copy(wrapText = it)) }
    PuppetToggle("console.gutter",      showGutter)  { onSettingsChange(settings.copy(showGutterStrip = it)) }
    PuppetToggle("console.timestamps",  showTimestamps) { onSettingsChange(settings.copy(showTimestamps = it)) }
    PuppetToggle("console.regexMode",   regexMode)   { regexMode = it }
    PuppetToggle("console.searchAsFilter", searchAsFilter) { searchAsFilter = it }
    PuppetToggle("console.searchOpen",  searchOpen)  { if (it) openSearch() else closeSearch() }
    PuppetField ("console.search", searchQuery)      { searchQuery = it }
    PuppetClick ("console.clearSearch", enabled = searchQuery.isNotEmpty()) { searchQuery = "" }
    PuppetClick ("console.saveToFile")   { gameConsole.exportEntries(logsCopy) }
    PuppetClick ("console.copyAll")      { copyAll() }
    PuppetClick ("console.clear", enabled = isLive) { if (isLive) gameConsole.clear() }
    PuppetClick ("console.jumpToBottom", enabled = filtered.isNotEmpty()) {
        scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
    }
    PuppetClick ("console.matchNext",    enabled = matchIndex.ranges.isNotEmpty()) { jumpNext() }
    PuppetClick ("console.matchPrev",    enabled = matchIndex.ranges.isNotEmpty()) { jumpPrev() }
    FONT_SIZES.forEach { sz ->
        PuppetClick("console.fontSize.$sz") { onSettingsChange(settings.copy(fontSize = sz)) }
    }

    // ── Root layout + key handler ───────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Command input owns its own keyboard handling (Enter / Up /
                // Down / Esc). When it has focus, the root passes ALL keys
                // through so typing isn't intercepted.
                if (cmdHasFocus) return@onPreviewKeyEvent false
                handleKey(
                    ev          = ev,
                    searchFocus = searchHasFocus,
                    matchCount  = matchIndex.ranges.size,
                    onOpenSearch = { openSearch() },
                    onCloseSearch = {
                        if (searchQuery.isNotEmpty()) searchQuery = "" else closeSearch()
                    },
                    onNextMatch  = ::jumpNext,
                    onPrevMatch  = ::jumpPrev,
                    onScrollTop  = { scope.launch { scrollState.scrollTo(0) } },
                    onScrollBottom = { scope.launch { scrollState.animateScrollTo(scrollState.maxValue) } },
                    onPageUp     = { scope.launch { scrollState.animateScrollBy(-scrollState.viewportSize.toFloat() * 0.9f) } },
                    onPageDown   = { scope.launch { scrollState.animateScrollBy( scrollState.viewportSize.toFloat() * 0.9f) } },
                    onLineUp     = { scope.launch { scrollState.animateScrollBy(-fontSize.toFloat() * 1.6f) } },
                    onLineDown   = { scope.launch { scrollState.animateScrollBy( fontSize.toFloat() * 1.6f) } },
                )
            },
    ) {
        // ── Toolbar ─────────────────────────────────────────────────────────
        Toolbar(
            strings       = s,
            filtered      = filtered.size,
            total         = logsCopy.size,
            warnCount     = warnCount,
            errorCount    = errorCount,
            filterInfo    = filterInfo,
            filterWarn    = filterWarn,
            filterError   = filterError,
            onFilterInfo  = { filterInfo = it },
            onFilterWarn  = { filterWarn = it },
            onFilterError = { filterError = it },
            wrapText      = wrapText,
            onToggleWrap  = { onSettingsChange(settings.copy(wrapText = !wrapText)) },
            fontSize      = fontSize,
            onFontSize    = { onSettingsChange(settings.copy(fontSize = it)) },
            showGutter    = showGutter,
            onToggleGutter = { onSettingsChange(settings.copy(showGutterStrip = !showGutter)) },
            showTimestamps = showTimestamps,
            onToggleTimestamps = { onSettingsChange(settings.copy(showTimestamps = !showTimestamps)) },
            onCopyAll     = { copyAll() },
            // Export what's on screen, not the live buffer: in a file-
            // backed Logs-tab view the live buffer may hold a different
            // pack's session, so exportEntries(logsCopy) writes the
            // session the user is actually looking at.
            onSave        = { gameConsole.exportEntries(logsCopy) },
            // Clear only acts on the live buffer; a file-backed view is
            // read-only, so the button no-ops there rather than wiping
            // the running session's buffer behind the user's back.
            onClear       = { if (isLive) gameConsole.clear() },
        )

        HorizontalDivider(thickness = 1.dp, color = themeColors.outline.copy(alpha = 0.4f))

        // ── Log area ────────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val hScroll = rememberScrollState()
            val baseStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize   = fontSize.sp,
                color      = themeColors.textPrimary,
            )

            // Gutter strip pixel width is independent of font size; 3 dp
            // reads as a clear severity tag without competing with the
            // text column for horizontal space. drawBehind sits BEFORE
            // padding in the modifier chain so its canvas covers the
            // field's outer box -- the bar lands flush with the window's
            // left edge while the text starts after the start padding,
            // leaving a clean gap. yOffsetPx folds the field's top
            // padding into each line's getLineTop so the bar aligns with
            // the actual rendered baseline rather than the canvas top.
            val gutterWidthPx = with(density) { 3.dp.toPx() }
            val verticalPaddingPx = with(density) { 4.dp.toPx() }
            val gutterModifier = if (showGutter) {
                Modifier.drawBehind {
                    val layout = layoutResult ?: return@drawBehind
                    drawSeverityGutter(
                        layout         = layout,
                        severities     = matchIndex.lineSeverities,
                        warnColor      = themeColors.warnAccent,
                        errorColor     = themeColors.criticalAccent,
                        gutterWidthPx  = gutterWidthPx,
                        yOffsetPx      = verticalPaddingPx,
                    )
                }
            } else {
                Modifier
            }

            // Override BasicTextField's built-in LocalTextContextMenu so
            // the right-click popup drops Cut / Paste (we are read-only)
            // and picks up our Copy line + Copy all entries. Keeps the
            // field's native Copy / Select-all so OS shortcuts and the
            // menu agree on behaviour.
            val customContextMenu = remember(s) {
                ConsoleTextContextMenu(
                    strings        = s,
                    onCopyLine     = ::copyLine,
                    onCopyAll      = ::copyAll,
                )
            }
            // Replace Compose Desktop's native-JPopupMenu representation
            // with a Compose-rendered one painted from CelestiaTheme.
            // Default on Linux pulls a Swing popup (dated, ignores theme,
            // user reported as ugly); the DefaultContextMenuRepresentation
            // constructor draws via Compose primitives and lets us pick
            // surface / text / hover colours straight from the active
            // palette. Atelier accent overrides flow through naturally.
            val menuRepresentation = remember(themeColors) {
                DefaultContextMenuRepresentation(
                    backgroundColor = themeColors.surface,
                    textColor       = themeColors.textPrimary,
                    itemHoverColor  = themeColors.primary.copy(alpha = 0.14f),
                )
            }
            CompositionLocalProvider(
                LocalTextContextMenu          provides customContextMenu,
                LocalContextMenuRepresentation provides menuRepresentation,
            ) {
            ContextMenuArea(
                items = {
                    listOf(
                        ContextMenuItem(s.consoleMenuCopyLine) { copyLine() },
                        ContextMenuItem(s.consoleMenuCopySelection) { copySelection() },
                        ContextMenuItem(s.consoleCopyAll) { copyAll() },
                    )
                },
            ) {
            if (wrapText) {
                // Wrap-on path: BasicTextField inherits its width from the
                // parent verticalScroll Column at fillMaxWidth, which is
                // exactly what enables wrap. Drag-select, Ctrl+A, Ctrl+C,
                // and F3 visual selection band all work natively.
                Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    BasicTextField(
                        value         = textFieldValue,
                        onValueChange = { tfv -> selection = tfv.selection },
                        readOnly      = true,
                        textStyle     = baseStyle,
                        cursorBrush   = SolidColor(themeColors.textPrimary),
                        onTextLayout  = { layoutResult = it },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .then(gutterModifier)
                            .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                            .focusRequester(logFocus),
                    )
                }
            } else {
                // Wrap-off path: BasicTextField has no softWrap parameter
                // and cannot escape its parent's fillMaxWidth constraint,
                // so a no-wrap rendering needs a different host. Text(
                // softWrap = false) measures at its intrinsic line width
                // and a horizontalScroll around the column then exposes
                // the overflow to scrolling. SelectionContainer gives
                // drag-select; the focusable outer Column carries the
                // kbd-chord focus the chord handler relies on. Ctrl+A
                // and the F3 selection band are unavailable in this
                // mode -- the yellow span highlights still mark every
                // match so navigation remains usable.
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .horizontalScroll(hScroll)
                        .focusRequester(logFocus)
                        .focusable(),
                ) {
                    SelectionContainer {
                        Text(
                            text         = matchIndex.annotated,
                            softWrap     = false,
                            style        = baseStyle,
                            onTextLayout = { layoutResult = it },
                            modifier     = Modifier
                                .then(gutterModifier)
                                .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            } // end ContextMenuArea
            } // end CompositionLocalProvider(LocalTextContextMenu)

            // Console-local scrollbar style: Compose Desktop's default is
            // 4 dp thick at 12% alpha, effectively invisible against the
            // console background. Bump to 8 dp + 40% unhover / 75% hover
            // so the bar is parseable peripherally without dominating the
            // text column.
            val scrollbarStyle = ScrollbarStyle(
                minimalHeight       = 24.dp,
                thickness           = 8.dp,
                shape               = RoundedCornerShape(4.dp),
                hoverDurationMillis = 250,
                unhoverColor        = themeColors.textSecondary.copy(alpha = 0.40f),
                hoverColor          = themeColors.textSecondary.copy(alpha = 0.75f),
            )
            VerticalScrollbar(
                adapter  = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style    = scrollbarStyle,
            )
            if (!wrapText) {
                HorizontalScrollbar(
                    adapter  = rememberScrollbarAdapter(hScroll),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    style    = scrollbarStyle,
                )
            }

            // Ephemeral "copied" toast. Crossfade keeps a steady boxshape
            // while the content swaps between "showing" and "hidden" --
            // AnimatedVisibility's scope-resolution issue avoided because
            // Crossfade has no scoped overload. Animation duration mirrors
            // the StyleSpec's idea of a quick microinteraction (mpv-OSD
            // style: appear on action, dissolve when idle).
            Crossfade(
                targetState   = copiedFlash,
                animationSpec = tween(180),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) { showing ->
                if (showing) {
                    Surface(color = themeColors.success.copy(alpha = 0.9f)) {
                        Text(
                            text     = s.consoleCopied,
                            // White text reads against both light- and dark-
                            // success surfaces in CelestiaColors as currently
                            // defined; revisit in customization slice if a
                            // contrast pairing becomes necessary under
                            // user-supplied overrides.
                            color    = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(0.dp))
                }
            }
        }

        // ── Footer: search prompt + status ─────────────────────────────────
        HorizontalDivider(thickness = 1.dp, color = themeColors.outline.copy(alpha = 0.4f))

        if (searchOpen) {
            SearchPrompt(
                query          = searchQuery,
                // Position preserved across query refinements; the
                // LaunchedEffect(matchIndex.ranges.size) clamp resets
                // currentMatch only when the new match set has fewer
                // entries than the cursor position. Refining 'NullPoint'
                // to 'NullPointer' keeps the user at hit 5 of 12 instead
                // of teleporting them back to the top.
                onQueryChange  = { searchQuery = it },
                regexMode      = regexMode,
                regexValid     = !regexMode || searchQuery.isBlank() || searchRegex != null,
                onToggleRegex  = { regexMode = !regexMode },
                filterMode     = searchAsFilter,
                onToggleFilter = { searchAsFilter = !searchAsFilter },
                onClose        = { closeSearch() },
                focusRequester = searchFocus,
                onFocusChanged = { searchHasFocus = it },
                strings        = s,
                onNext         = ::jumpNext,
                onPrev         = ::jumpPrev,
            )
        }

        // Command-input row: visible only while a game process is alive
        // (gameConsole.canSendCommands tracks the LaunchDriver's
        // attach / detach lifecycle). Enter sends the line to the game
        // process's stdin via gameConsole.sendCommand, pushes it onto
        // cmdHistory, clears the input. Up / Down step through history
        // (most-recent first). Esc clears or blurs.
        if (isLive && gameConsole.canSendCommands) {
            CommandInputRow(
                value            = cmdInput,
                onValueChange    = { cmdInput = it; cmdHistoryIdx = -1 },
                onSubmit         = {
                    val txt = cmdInput.trim()
                    if (txt.isNotEmpty()) {
                        gameConsole.sendCommand(txt)
                        // Echo the command into the buffer so the user
                        // sees what they typed -- the game's stdout
                        // reply lands on its own subsequent lines.
                        gameConsole.append("> $txt", LogType.INFO)
                        cmdHistory.add(0, txt)
                        if (cmdHistory.size > 200) cmdHistory.removeAt(cmdHistory.size - 1)
                        cmdInput = ""
                        cmdHistoryIdx = -1
                    }
                },
                onHistoryPrev    = {
                    if (cmdHistory.isEmpty()) return@CommandInputRow
                    val next = (cmdHistoryIdx + 1).coerceAtMost(cmdHistory.size - 1)
                    cmdHistoryIdx = next
                    cmdInput = cmdHistory[next]
                },
                onHistoryNext    = {
                    if (cmdHistoryIdx <= 0) {
                        cmdHistoryIdx = -1
                        cmdInput = ""
                    } else {
                        cmdHistoryIdx -= 1
                        cmdInput = cmdHistory[cmdHistoryIdx]
                    }
                },
                onEscape         = {
                    if (cmdInput.isNotEmpty()) {
                        cmdInput = ""
                        cmdHistoryIdx = -1
                    } else {
                        focusManager.clearFocus()
                        runCatching { logFocus.requestFocus() }
                    }
                },
                onFocusChanged   = { cmdHasFocus = it },
                strings          = s,
            )
        }

        StatusFooter(
            strings        = s,
            filtered       = filtered.size,
            total          = logsCopy.size,
            historyOffset  = historyOffset,
            warnCount      = warnCount,
            errorCount     = errorCount,
            following      = isAtBottom,
            searchActive   = effectiveQuery.isNotBlank(),
            matchCurrent   = if (matchIndex.ranges.isNotEmpty()) currentMatch + 1 else 0,
            matchTotal     = matchIndex.ranges.size,
            onResumeFollow = {
                scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
            },
        )
    }
}

// ── Toolbar ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Toolbar(
    strings: AppStrings,
    filtered: Int,
    total: Int,
    warnCount: Int,
    errorCount: Int,
    filterInfo: Boolean,
    filterWarn: Boolean,
    filterError: Boolean,
    onFilterInfo: (Boolean) -> Unit,
    onFilterWarn: (Boolean) -> Unit,
    onFilterError: (Boolean) -> Unit,
    wrapText: Boolean,
    onToggleWrap: () -> Unit,
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    showGutter: Boolean,
    onToggleGutter: () -> Unit,
    showTimestamps: Boolean,
    onToggleTimestamps: () -> Unit,
    onCopyAll: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = CelestiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text       = strings.consoleHeaderCount(filtered, total),
            color      = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize   = 11.sp,
            modifier   = Modifier.padding(start = 4.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityToggle("INFO",  filterInfo,  colors.textPrimary,    null,       onFilterInfo)
            Spacer(Modifier.width(4.dp))
            SeverityToggle("WARN",  filterWarn,  colors.warnAccent,     warnCount,  onFilterWarn)
            Spacer(Modifier.width(4.dp))
            SeverityToggle("ERROR", filterError, colors.criticalAccent, errorCount, onFilterError)

            Spacer(Modifier.width(8.dp))
            VerticalDivider(
                modifier = Modifier.height(20.dp).width(1.dp),
                color    = colors.textSecondary.copy(alpha = 0.3f),
            )
            Spacer(Modifier.width(4.dp))

            // Font size: click cycles through FONT_SIZES, scroll-wheel
            // increments / decrements by 1 sp. Wheel-over-control is the
            // pattern used by Hyprland / Discord / mpv -- intuitive for
            // users who reach for the mouse to adjust. The 1-sp step also
            // exposes the full 8..22 range, not just the three FONT_SIZES
            // presets.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        val idx = FONT_SIZES.indexOf(fontSize).takeIf { it >= 0 } ?: 0
                        onFontSize(FONT_SIZES[(idx + 1) % FONT_SIZES.size])
                    }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val delta = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (delta == 0f) return@onPointerEvent
                        // scrollDelta.y is positive for wheel-down on most
                        // platforms; invert so up = bigger text.
                        val step = if (delta < 0) 1 else -1
                        onFontSize(
                            (fontSize + step).coerceIn(
                                ConsoleSettings.MIN_FONT_SIZE,
                                ConsoleSettings.MAX_FONT_SIZE,
                            ),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "${fontSize}px",
                    color      = colors.textSecondary,
                    fontSize   = 11.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            IconButton(onClick = onToggleWrap, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.WrapText,
                    contentDescription = strings.consoleWrap,
                    tint = if (wrapText) colors.success else colors.textSecondary,
                )
            }
            IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Save, strings.consoleSaveToFile, tint = colors.textSecondary)
            }
            IconButton(onClick = onCopyAll, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, strings.consoleCopyAll, tint = colors.textSecondary)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, strings.consoleClear, tint = colors.textSecondary)
            }

            // In-window gear: quick-access menu for the persisted toggles
            // (gutter strip, timestamps) that don't belong on the main
            // toolbar bar but should be one click away.
            var gearOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { gearOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, strings.consoleSettingsLabel, tint = colors.textSecondary)
                }
                DropdownMenu(
                    expanded         = gearOpen,
                    onDismissRequest = { gearOpen = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (showGutter) strings.consoleHideGutter else strings.consoleShowGutter,
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 11.sp,
                            )
                        },
                        onClick = { onToggleGutter(); gearOpen = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (showTimestamps) strings.consoleHideTimestamps else strings.consoleShowTimestamps,
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 11.sp,
                            )
                        },
                        onClick = { onToggleTimestamps(); gearOpen = false },
                    )
                }
            }
        }
    }
}

// Flat severity toggle. Material3 TextButton injects its own content-color
// CompositionLocal that overrides Text(color = ...) in this codebase's
// theme stack, making the labels read as monochrome at low contrast. A
// raw Box + clickable bypasses that pipeline and gives us full control
// over both the chip background tint (active state) and the text color.
// Active chip carries a 14% accent wash so the toggle reads ON without
// needing a border; inactive label drops to 45% alpha for clear hierarchy.
@Composable
private fun SeverityToggle(
    label: String,
    active: Boolean,
    accent: Color,
    count: Int?,
    onToggle: (Boolean) -> Unit,
) {
    val text = if (count != null && count > 0) "$label $count" else label
    val bg   = if (active) accent.copy(alpha = 0.14f) else Color.Transparent
    // Wrap-content height + symmetric padding centers the text optically.
    // The prior fixed 24 dp height pushed all-caps mono labels visually
    // below the chip center because font ascent reserves space above the
    // caps that no glyph fills. Letting the box hug the text gives an
    // even top / bottom margin around the actual ink.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable { onToggle(!active) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = text,
            color      = if (active) accent else accent.copy(alpha = 0.45f),
            fontSize   = 11.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ── Search prompt (footer-row, less/helix-style `/` entry) ──────────────────

@Composable
private fun SearchPrompt(
    query: String,
    onQueryChange: (String) -> Unit,
    regexMode: Boolean,
    regexValid: Boolean,
    onToggleRegex: () -> Unit,
    filterMode: Boolean,
    onToggleFilter: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    strings: AppStrings,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val colors = CelestiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = "/",
            color      = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize   = 12.sp,
            modifier   = Modifier.padding(end = 8.dp),
        )
        BasicTextField(
            value         = query,
            onValueChange = onQueryChange,
            singleLine    = true,
            textStyle     = TextStyle(
                color      = colors.textPrimary,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush   = SolidColor(colors.textPrimary),
            modifier      = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text       = strings.consoleSearchPlaceholder,
                        color      = colors.textSecondary.copy(alpha = 0.5f),
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            },
        )
        val regexTint = when {
            !regexMode  -> colors.textSecondary.copy(alpha = 0.4f)
            !regexValid -> colors.criticalAccent
            else        -> colors.success
        }
        PromptButton(onClick = onToggleRegex) {
            Text(
                text       = ".*",
                color      = regexTint,
                fontSize   = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        // Filter toggle: off = search highlights + F3-navigates the full
        // buffer; on = buffer collapses to only matching lines (plus
        // dividers). 'f|' glyph reads as "filter on" without needing an
        // icon import. Active state is the same accent as a confirmed
        // regex toggle so the two related controls share a visual.
        PromptButton(onClick = onToggleFilter) {
            Text(
                text       = "f|",
                color      = if (filterMode) colors.success else colors.textSecondary.copy(alpha = 0.4f),
                fontSize   = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        PromptButton(onClick = onPrev) {
            Text(
                text       = "<",
                color      = colors.textSecondary,
                fontSize   = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        PromptButton(onClick = onNext) {
            Text(
                text       = ">",
                color      = colors.textSecondary,
                fontSize   = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        PromptButton(onClick = onClose) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = null,
                tint               = colors.textSecondary,
                modifier           = Modifier.size(14.dp),
            )
        }
    }
}

// Material3 TextButton injects a contentColor CompositionLocal that
// overrides our explicit Text(color = ...) settings in this theme stack;
// the regex / filter / prev / next labels on the SearchPrompt rendered
// as illegible smears the same way the severity toggles did. Bare
// Box + clickable hands us back full control over typography colors and
// fits the slim 24 dp footer height without TextButton's hidden minWidth.
@Composable
private fun PromptButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ── Command input row (live while game process is running) ─────────────────

@Composable
private fun CommandInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHistoryPrev: () -> Unit,
    onHistoryNext: () -> Unit,
    onEscape: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    strings: AppStrings,
) {
    val colors = CelestiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Right-arrow prompt glyph reads as "you type here, it goes
        // into the process". Mirrors the search prompt's '/' affordance
        // so the two footer rows look like a family.
        Text(
            text       = ">",
            color      = colors.success,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize   = 12.sp,
            modifier   = Modifier.padding(end = 8.dp),
        )
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = true,
            textStyle     = TextStyle(
                color      = colors.textPrimary,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush   = SolidColor(colors.textPrimary),
            modifier      = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
                        Key.DirectionUp            -> { onHistoryPrev(); true }
                        Key.DirectionDown          -> { onHistoryNext(); true }
                        Key.Escape                 -> { onEscape(); true }
                        else                       -> false
                    }
                },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text       = strings.consoleCommandPlaceholder,
                        color      = colors.textSecondary.copy(alpha = 0.45f),
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            },
        )
    }
}

// ── Status footer (always visible) ──────────────────────────────────────────

@Composable
private fun StatusFooter(
    strings: AppStrings,
    filtered: Int,
    total: Int,
    historyOffset: Int,
    warnCount: Int,
    errorCount: Int,
    following: Boolean,
    searchActive: Boolean,
    matchCurrent: Int,
    matchTotal: Int,
    onResumeFollow: () -> Unit,
) {
    val colors = CelestiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // When the sliding window has dropped entries to disk, report
        // both the live count and the on-disk total so the user knows
        // scrolling up will reach further than the in-memory size.
        val linesText = if (historyOffset > 0) {
            strings.consoleStatusLinesWithHistory(filtered, total, historyOffset)
        } else {
            strings.consoleStatusLines(filtered, total)
        }
        Text(
            text       = linesText,
            color      = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize   = 10.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text       = strings.consoleStatusFiltered(warnCount, errorCount),
            color      = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize   = 10.sp,
        )

        Spacer(Modifier.weight(1f))

        if (searchActive) {
            Text(
                text       = strings.consoleStatusMatch(matchCurrent, matchTotal),
                color      = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize   = 10.sp,
            )
            Spacer(Modifier.width(12.dp))
        }

        // Follow / paused chip: clickable when paused to resume tailing.
        // success doubles as "everything is on track"; pause uses warm
        // orange so the chip reads as a deliberate halt rather than an
        // error -- criticalAccent would conflate with ERROR severity.
        val followText  = if (following) strings.consoleStatusFollow else strings.consoleStatusPaused
        val followColor = if (following) colors.success else CONSOLE_PAUSE_ACCENT
        TextButton(onClick = onResumeFollow, modifier = Modifier.height(20.dp)) {
            Text(
                text       = followText,
                color      = followColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize   = 10.sp,
            )
        }
    }
}

// ── Keyboard handler ────────────────────────────────────────────────────────
// Dispatched from the root `onPreviewKeyEvent` so chords fire regardless of
// which inner field has focus. The search-focus guard distinguishes "user is
// typing in the prompt" from "user is navigating the log".

private fun handleKey(
    ev: KeyEvent,
    searchFocus: Boolean,
    matchCount: Int,
    onOpenSearch:   () -> Unit,
    onCloseSearch:  () -> Unit,
    onNextMatch:    () -> Unit,
    onPrevMatch:    () -> Unit,
    onScrollTop:    () -> Unit,
    onScrollBottom: () -> Unit,
    onPageUp:       () -> Unit,
    onPageDown:     () -> Unit,
    onLineUp:       () -> Unit,
    onLineDown:     () -> Unit,
): Boolean {
    val key = ev.key

    // Search-prompt-focused: only Escape, F3 / Shift+F3 are intercepted.
    if (searchFocus) {
        return when {
            key == Key.Escape          -> { onCloseSearch(); true }
            key == Key.F3 && ev.isShiftPressed -> { onPrevMatch(); true }
            key == Key.F3              -> { onNextMatch(); true }
            else -> false
        }
    }

    // Log-area-focused chords. Shift-modified keys are passed through so
    // BasicTextField selection extension (Shift+Arrow, Shift+End, etc.)
    // continues to work -- a chord with Shift always means "extend
    // selection" first, "navigate" only when Shift is up.
    val shift = ev.isShiftPressed
    val ctrl  = ev.isCtrlPressed
    return when {
        // Open search prompt. `?` (Shift+Slash) reserved for future
        // backward-search; today it falls through to the field.
        key == Key.F     && ctrl                -> { onOpenSearch(); true }
        key == Key.Slash && !shift              -> { onOpenSearch(); true }

        // Match nav (when there are matches). F3 + Shift+F3 always work;
        // bare n/N intercepted only when search has registered matches.
        key == Key.F3 && shift                            -> { onPrevMatch(); true }
        key == Key.F3                                     -> { onNextMatch(); true }
        matchCount > 0 && key == Key.N && shift           -> { onPrevMatch(); true }
        matchCount > 0 && key == Key.N && !shift          -> { onNextMatch(); true }

        // Scroll anchors. Ctrl+Home / Ctrl+End never conflict with
        // selection-extension since BasicTextField only honors plain
        // Home / End / Shift+Home / Shift+End on selection.
        key == Key.MoveHome && ctrl && !shift  -> { onScrollTop(); true }
        key == Key.MoveEnd  && ctrl && !shift  -> { onScrollBottom(); true }
        key == Key.G        && shift           -> { onScrollBottom(); true }
        key == Key.G        && !shift          -> { onScrollTop(); true }

        // Pagewise scroll. Page keys with Shift extend selection -- pass
        // through. Ctrl+u/d are vim-style halfpage; same Shift guard.
        key == Key.PageUp     && !shift        -> { onPageUp(); true }
        key == Key.PageDown   && !shift        -> { onPageDown(); true }
        key == Key.U && ctrl  && !shift        -> { onPageUp(); true }
        key == Key.D && ctrl  && !shift        -> { onPageDown(); true }

        // Linewise scroll. Arrow keys with Shift are selection-extension
        // chords -- pass through unmodified. Vim j/k bare-key only.
        (key == Key.K || key == Key.DirectionUp)   && !shift -> { onLineUp(); true }
        (key == Key.J || key == Key.DirectionDown) && !shift -> { onLineDown(); true }

        else -> false
    }
}

// ── AnnotatedString builder ─────────────────────────────────────────────────
// Single pass over `filtered`: emit one line per entry with severity color,
// apply ERROR_MARKERS regex highlight, apply search highlight + collect each
// match's absolute offset for F3/n jumping. DIVIDERs render inline as their
// own dimmed line.

private fun buildConsoleAnnotated(
    entries: List<LogEntry>,
    rawQuery: String,
    regexMode: Boolean,
    regexCompiled: Regex?,
    palette: ConsolePalette,
    showTimestamps: Boolean,
): MatchIndex {
    val matches = mutableListOf<IntRange>()
    val severities = mutableListOf<LineSeverity>()
    val query = rawQuery

    val annotated = buildAnnotatedString {
        for ((idx, e) in entries.withIndex()) {
            val lineStart = length
            val lineText: String
            val severityColor: Color

            if (e.type == LogType.DIVIDER) {
                lineText = e.text
                severityColor = palette.divider
                withStyle(SpanStyle(color = severityColor, fontWeight = FontWeight.Light)) {
                    append(lineText)
                }
            } else {
                severityColor = when (e.type) {
                    LogType.INFO  -> palette.severityInfo
                    LogType.WARN  -> palette.severityWarn
                    LogType.ERROR -> palette.severityError
                    LogType.DIVIDER -> palette.divider
                }
                lineText = if (showTimestamps) "[${e.timestamp}] ${e.text}" else e.text
                withStyle(SpanStyle(color = severityColor)) {
                    append(lineText)
                }
                if (e.type == LogType.ERROR || e.type == LogType.WARN) {
                    ERROR_MARKERS.findAll(lineText).forEach { m ->
                        addStyle(
                            SpanStyle(color = palette.severityError, fontWeight = FontWeight.Bold),
                            lineStart + m.range.first,
                            lineStart + m.range.last + 1,
                        )
                    }
                }
            }
            severities.add(LineSeverity(lineStart, length, e.type))

            // Search highlight + match-offset collection, scanning the
            // just-appended line text directly (no builder readback).
            if (query.isNotBlank()) {
                val matchRanges = if (regexMode) {
                    regexCompiled?.findAll(lineText)?.map { it.range }?.toList().orEmpty()
                } else {
                    findAllSubstring(lineText, query, ignoreCase = true)
                }
                matchRanges.forEach { range ->
                    if (range.isEmpty()) return@forEach
                    val absStart = lineStart + range.first
                    val absEnd   = lineStart + range.last + 1
                    addStyle(
                        SpanStyle(
                            color      = palette.searchMatch,
                            background = palette.searchMatchBg,
                            fontWeight = FontWeight.Bold,
                        ),
                        absStart,
                        absEnd,
                    )
                    // Inclusive end so the IntRange size mirrors the match span.
                    matches.add(absStart until absEnd)
                }
            }

            if (idx != entries.lastIndex) append('\n')
        }
    }

    return MatchIndex(
        annotated      = annotated,
        ranges         = matches,
        lineSeverities = severities,
    )
}

// Replaces the default BasicTextField right-click menu (Cut / Copy /
// Paste / Select all) with one that drops Cut + Paste (the field is
// read-only, those are useless) and adds Copy line / Copy all + keeps
// native Copy + Select all. The styling still flows through Compose
// Desktop's ContextMenuRepresentation; further visual polish lives on
// Phase 7.5.
@OptIn(ExperimentalFoundationApi::class)
private class ConsoleTextContextMenu(
    private val strings: AppStrings,
    private val onCopyLine: () -> Unit,
    private val onCopyAll: () -> Unit,
) : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        ContextMenuArea(
            items = {
                buildList {
                    val copyAction = textManager.copy
                    if (copyAction != null && copyAction.enabled) {
                        add(ContextMenuItem(strings.consoleMenuCopySelection) { copyAction.execute() })
                    }
                    add(ContextMenuItem(strings.consoleMenuCopyLine) { onCopyLine() })
                    add(ContextMenuItem(strings.consoleCopyAll) { onCopyAll() })
                    val selectAllAction = textManager.selectAll
                    if (selectAllAction != null && selectAllAction.enabled) {
                        add(ContextMenuItem(strings.consoleSelectAll) { selectAllAction.execute() })
                    }
                }
            },
            state   = state,
            content = content,
        )
    }
}

// Paint a thin vertical strip at the left edge of each rendered line whose
// severity isn't INFO / DIVIDER. WARN / ERROR each get their own accent.
// Layout-source-of-truth: the TextLayoutResult captured by the field /
// Text's onTextLayout; runCatching guards keep a transient stale layout
// from crashing the paint pass while the buffer catches up.
private fun DrawScope.drawSeverityGutter(
    layout: TextLayoutResult,
    severities: List<LineSeverity>,
    warnColor: Color,
    errorColor: Color,
    gutterWidthPx: Float,
    yOffsetPx: Float,
) {
    if (severities.isEmpty()) return
    val textLen = layout.layoutInput.text.length
    if (textLen == 0) return
    for (sev in severities) {
        if (sev.type == LogType.INFO || sev.type == LogType.DIVIDER) continue
        val color = if (sev.type == LogType.ERROR) errorColor else warnColor
        val safeStart = sev.startOffset.coerceIn(0, textLen - 1)
        val safeEnd   = (sev.endOffset - 1).coerceIn(0, textLen - 1)
        if (safeStart > safeEnd) continue
        val startLine = runCatching { layout.getLineForOffset(safeStart) }.getOrNull() ?: continue
        val endLine   = runCatching { layout.getLineForOffset(safeEnd) }.getOrNull() ?: continue
        for (line in startLine..endLine) {
            val top    = runCatching { layout.getLineTop(line) }.getOrNull() ?: continue
            val bottom = runCatching { layout.getLineBottom(line) }.getOrNull() ?: continue
            drawRect(
                color   = color,
                topLeft = Offset(0f, yOffsetPx + top),
                size    = Size(gutterWidthPx, bottom - top),
            )
        }
    }
}

private fun findAllSubstring(text: String, query: String, ignoreCase: Boolean): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var i = text.indexOf(query, ignoreCase = ignoreCase)
    while (i >= 0) {
        out.add(i until i + query.length)
        // Non-overlapping (matches less / grep / IDE find semantics):
        // "aa" in "aaaa" -> hits (0..1) and (2..3), not (0..1)(1..2)(2..3).
        i = text.indexOf(query, startIndex = i + query.length, ignoreCase = ignoreCase)
    }
    return out
}

