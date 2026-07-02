package hivens.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.generated.resources.Res
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.StyleSpec
import hivens.ui.theme.nexiraBrailleFamily
import hivens.ui.utils.ConsoleSettings
import hivens.ui.utils.FilterRule
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.HighlightRule
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import java.awt.datatransfer.StringSelection
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// Severity-only highlight + the exception markers that survive Slice A.
// Class-name / number / null highlights were noisy and lied when log
// formats shifted; cut. User-extensible rule list arrives with Slice C.
private val ERROR_MARKERS = Regex("(Exception|Error|FATAL|SEVERE|Caused by:|\\bat )")
private val FONT_SIZES = listOf(11, 12, 14)

// Upper bound on search-highlight spans / match offsets kept per render. A broad
// query ("e") over a 50k-line buffer would otherwise allocate tens of thousands of
// DocSpans + IntRanges + AnnotatedString entries -- a memory spike out of all
// proportion to a highlight aid. Past this, scanning stops: F3 navigates the first
// MAX_SEARCH_MATCHES hits, which is far more than anyone steps through by hand.
private const val MAX_SEARCH_MATCHES = 5000

// ── Palette ──────────────────────────────────────────────────────────────────
// Theme-derived colors flow through NxTheme.colors at every composable
// call site; this small record carries the subset that pure helpers (the
// AnnotatedString builder) consume off the composition. Only console-only
// tokens (the yellow search highlight, the orange pause accent) live as
// constants -- everything else maps to a NxColors role and follows
// the user's theme + customization overrides.
internal data class ConsolePalette(
    val textPrimary:    Color,
    val textSecondary:  Color,
    val severityInfo:   Color,
    val severityWarn:   Color,
    val severityError:  Color,
    val divider:        Color,
    val searchMatch:    Color,
    val searchMatchBg:  Color,
)

// Console-only accents that have no NxColors counterpart. Yellow
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
// The full off-thread render result: the annotated document, F3/n match ranges,
// the per-entry severity spans for the gutter, plus the scalar counts the
// toolbar / footer show. Everything the UI needs is computed once on
// Dispatchers.Default and swapped in together, so composition stays O(1).
internal data class ConsoleRender(
    val annotated:      AnnotatedString,
    val ranges:         List<IntRange>,
    val lineSeverities: List<LineSeverity>,
    val filteredCount:  Int,
    val totalCount:     Int,
    val warnCount:      Int,
    val errorCount:     Int,
    /** true when search matches hit MAX_SEARCH_MATCHES and scanning stopped early. */
    val searchCapped:   Boolean = false,
)

private val EMPTY_RENDER = ConsoleRender(AnnotatedString(""), emptyList(), emptyList(), 0, 0, 0, 0)

internal data class LineSeverity(
    val startOffset: Int,
    val endOffset:   Int,
    val type:        LogType,
)

// Colour role of one span, resolved to an actual SpanStyle only in the styling
// pass. Keeping the structural pass palette-free is what lets a theme change
// skip the expensive filter/regex/annotate rebuild -- see [buildConsoleDoc].
internal enum class SpanRole { Divider, Info, Warn, Error, Marker, Search }

// [colorHex] != null is a user highlight rule: an explicit colour (and [bold])
// that overrides the role->palette mapping for this span. Emitted right after the
// base line span so it wins over severity colour, but before marker / search
// overlays so those stay visible on their sub-ranges.
internal class DocSpan(
    val start: Int,
    val end: Int,
    val role: SpanRole,
    val colorHex: String? = null,
    val bold: Boolean = false,
)

// Palette-independent render document: the plain text, the span layout (roles
// not colours), the F3/n match ranges, the gutter severities, and the scalar
// counts. Built once per content / filter / search change; [styleDoc] colours
// it per palette.
internal data class ConsoleDoc(
    val text:           String,
    val spans:          List<DocSpan>,
    val ranges:         List<IntRange>,
    val lineSeverities: List<LineSeverity>,
    val filteredCount:  Int,
    val totalCount:     Int,
    val warnCount:      Int,
    val errorCount:     Int,
    /** true when search matches hit MAX_SEARCH_MATCHES and scanning stopped early. */
    val searchCapped:   Boolean = false,
)

private val EMPTY_DOC = ConsoleDoc("", emptyList(), emptyList(), emptyList(), 0, 0, 0, 0)

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
        // NxTheme handles both the Material colorScheme + the
        // launcher's NxColors composition local; child composables
        // read NxTheme.colors directly. Accent / role overrides
        // from LocalCustomization propagate in if the caller wrapped the
        // ConsoleWindow site in a CustomizationProvider; otherwise the
        // default settings yield the same palette as the main shell.
        NxTheme(
            useDarkTheme = isDarkTheme,
            customTheme  = customTheme,
            style        = style,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = NxTheme.colors.background) {
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
    val themeColors = NxTheme.colors

    // Pure-function helpers (the AnnotatedString builder) consume a value-
    // type palette off the composition; build it once per theme change so
    // the builder stays @Composable-free.
    val palette = remember(themeColors, settings.infoColor, settings.warnColor, settings.errorColor) {
        ConsolePalette(
            textPrimary    = themeColors.textPrimary,
            textSecondary  = themeColors.textSecondary,
            // Settings > Console severity overrides win; null falls back to theme.
            severityInfo   = settings.infoColor?.let { CustomTheme.parseHexColor(it) } ?: themeColors.textPrimary,
            severityWarn   = settings.warnColor?.let { CustomTheme.parseHexColor(it) } ?: themeColors.warnAccent,
            severityError  = settings.errorColor?.let { CustomTheme.parseHexColor(it) } ?: themeColors.criticalAccent,
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

    // ── Buffer source ──────────────────────────────────────────────────────
    // Live source: the service publishes a coalesced, off-thread ConsoleSnapshot
    // -- ingestion, file IO, and the buffer copy all run on its drainer, never on
    // Main -- so the consumer just reads the latest immutable list. A modded MC
    // start floods 5k+ lines in ~2s; those collapse into a handful of snapshots.
    // File source: a static, parsed list.
    val liveSnapshot by gameConsole.snapshot.collectAsState()
    val entries = when (source) {
        is ConsoleSource.Live       -> liveSnapshot.entries
        is ConsoleSource.FileBacked -> source.entries
    }
    val historyOffset = if (isLive) liveSnapshot.historyOffset else 0

    // ── Search query debounce ──────────────────────────────────────────────
    // Highlight + match-offset rebuild keys off the debounced value;
    // counts and the prompt text key off the raw one so the user sees
    // their typing immediately.
    var effectiveQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(120.milliseconds)
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

    // ── Render: filter + counts + annotate, all OFF the UI thread ──────────
    // The whole O(n) pass -- severity/query filtering, warn/error counts, and
    // the AnnotatedString build -- runs on Dispatchers.Default and swaps in once
    // via produceState. Nothing O(n) touches composition, so a 5000-line buffer
    // (or a live flood) never blocks Main. In-flight builds cancel when any key
    // changes, so rapid filter/search edits collapse to one final rebuild.
    // First-render fallback is an empty render; the cold-open gap is sub-frame.
    //
    // Two-stage filter: severity gates first, then optional query-narrowing when
    // search-as-filter is on (the default leaves search a pure highlight + F3
    // aid). Dividers are kept verbatim so session boundaries stay visible.
    // Stage 1 -- structural pass, OFF the UI thread and palette-free. The whole
    // O(n) cost (severity/query filter, regex, warn/error counts, span layout)
    // runs only when content, filters, search, or timestamps change. A theme
    // tick does NOT re-run this: palette is deliberately not a key here.
    val doc by produceState(
        initialValue = remember { EMPTY_DOC },
        entries, filterInfo, filterWarn, filterError,
        searchAsFilter, effectiveQuery, regexMode, searchRegex, showTimestamps,
        settings.highlightRules, settings.filterRules,
    ) {
        value = withContext(Dispatchers.Default) {
            buildConsoleDoc(
                all            = entries,
                filterInfo     = filterInfo,
                filterWarn     = filterWarn,
                filterError    = filterError,
                searchAsFilter = searchAsFilter,
                rawQuery       = effectiveQuery,
                regexMode      = regexMode,
                regexCompiled  = searchRegex,
                showTimestamps = showTimestamps,
                highlightRules = settings.highlightRules,
                filterRules    = settings.filterRules,
            )
        }
    }

    // Stage 2 -- styling pass: apply palette colours to the precomputed spans.
    // Keyed on the doc instance (stable across a pure palette tick) plus the
    // palette, so a theme change re-runs only this cheap span-colour pass, never
    // the filter/regex above. Still off-thread so a content flood never styles a
    // 5000-line buffer on Main.
    val render by produceState(
        initialValue = remember { EMPTY_RENDER },
        doc, palette,
    ) {
        value = withContext(Dispatchers.Default) { styleDoc(doc, palette) }
    }

    // Clamp current match index when the match set shrinks past it.
    LaunchedEffect(render.ranges.size) {
        if (currentMatch >= render.ranges.size) currentMatch = 0
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
    LaunchedEffect(render.annotated) {
        if (isAtBottom) scrollState.scrollTo(scrollState.maxValue)
    }

    // ── Lazy history page-in (sliding window upper edge) ───────────────────
    // When the user scrolls within 80 px of the top AND the service has
    // entries dropped past the window, page the older entries back in.
    // The load is one-shot: a `loading` flag suppresses re-entry while the
    // batch is in flight, otherwise rapid scroll-to-top events would queue
    // overlapping reads. The drainer prepends the loaded entries and the next
    // published snapshot carries them; we shift scrollState by the approximate
    // height of the loaded block so the user's visual line stays put.
    var historyLoading by remember { mutableStateOf(false) }
    // historyOffset comes from the published snapshot (live only); file-backed
    // views load the whole tail-bounded file up front, so there is nothing to
    // page. loadHistoryBefore prepends to the buffer on the drainer and the next
    // snapshot carries the older entries; we only do the scroll-anchor shift.
    LaunchedEffect(scrollState.value, historyOffset, isLive) {
        if (!isLive) return@LaunchedEffect
        if (historyLoading) return@LaunchedEffect
        if (historyOffset <= 0) return@LaunchedEffect
        if (scrollState.value > 80) return@LaunchedEffect
        historyLoading = true
        try {
            val loaded = gameConsole.loadHistoryBefore(count = 500)
            if (loaded.isNotEmpty()) {
                // ScrollState shifts by an approximate line height per loaded
                // entry so the user's visual line stays put; not exact (wrap
                // line height depends on glyph metrics) but the miss is sub-100 px.
                val approxLineHeightPx = with(density) { (fontSize * 1.4f).sp.toPx() }
                val shift = (loaded.size * approxLineHeightPx).toInt()
                scrollState.scrollTo((scrollState.value + shift).coerceAtMost(scrollState.maxValue))
            }
        } finally {
            historyLoading = false
        }
    }

    // ── TextFieldValue source of truth ─────────────────────────────────────
    // Text comes from `render.annotated` (system-controlled). Selection
    // is user-controlled and persists across rebuilds; the read-only field
    // only routes selection changes (drag-select, Ctrl+A, click-position)
    // through onValueChange.
    val textFieldValue = remember(render.annotated, selection) {
        TextFieldValue(annotatedString = render.annotated, selection = selection)
    }

    // ── Copy actions ───────────────────────────────────────────────────────
    fun copyAll() {
        val text = entries.joinToString("\n") { e ->
            if (e.type == LogType.DIVIDER) e.text else "[${e.timestamp}] ${e.text}"
        }
        scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
        copiedFlash = true
        scope.launch {
            delay(900.milliseconds)
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
        val annotated = render.annotated
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
            delay(900.milliseconds)
            copiedFlash = false
        }
    }

    // Copy the active selection. Collapsed selection (no drag) -> no-op;
    // the user can switch to copy-line for that case via the same menu.
    fun copySelection() {
        if (selection.collapsed) return
        val annotated = render.annotated
        val start = selection.start.coerceIn(0, annotated.length)
        val end   = selection.end.coerceIn(0, annotated.length)
        if (start >= end) return
        val text = annotated.substring(start, end)
        scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
        copiedFlash = true
        scope.launch {
            delay(900.milliseconds)
            copiedFlash = false
        }
    }

    // ── Match jumping ──────────────────────────────────────────────────────
    // The layout result may be from the PREVIOUS frame's BasicTextField measure
    // while render.ranges references char positions in the JUST-rebuilt
    // annotated string. Under flood, an offset can exceed the old layout's
    // text length -- MultiParagraph.getLineForOffset would throw. Guard on
    // text length first, then runCatching the layout call so a transient
    // out-of-range only loses one F3 press instead of crashing.
    fun scrollToMatch(idx: Int) {
        val layout = layoutResult ?: return
        if (idx !in render.ranges.indices) return
        val range = render.ranges[idx]
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
        val safeEnd = end.coerceAtMost(render.annotated.length)
        selection = TextRange(start, safeEnd)
    }

    fun jumpNext() {
        if (render.ranges.isEmpty()) return
        currentMatch = (currentMatch + 1) % render.ranges.size
        scrollToMatch(currentMatch)
    }

    fun jumpPrev() {
        if (render.ranges.isEmpty()) return
        currentMatch = if (currentMatch == 0) render.ranges.lastIndex
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
    PuppetClick ("console.saveToFile")   { gameConsole.exportEntries(entries) }
    PuppetClick ("console.copyAll")      { copyAll() }
    PuppetClick ("console.clear", enabled = isLive) { if (isLive) gameConsole.clear() }
    PuppetClick ("console.jumpToBottom", enabled = render.filteredCount > 0) {
        scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
    }
    PuppetClick ("console.matchNext",    enabled = render.ranges.isNotEmpty()) { jumpNext() }
    PuppetClick ("console.matchPrev",    enabled = render.ranges.isNotEmpty()) { jumpPrev() }
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
                    matchCount  = render.ranges.size,
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
            filtered      = render.filteredCount,
            total         = entries.size,
            warnCount     = render.warnCount,
            errorCount    = render.errorCount,
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
            // pack's session, so exportEntries(entries) writes the
            // session the user is actually looking at.
            onSave        = { gameConsole.exportEntries(entries) },
            // Clear only acts on the live buffer; a file-backed view is
            // read-only, so the button no-ops there rather than wiping
            // the running session's buffer behind the user's back.
            onClear       = { if (isLive) gameConsole.clear() },
        )

        HorizontalDivider(thickness = 1.dp, color = themeColors.outline.copy(alpha = 0.4f))

        // ── Log area ────────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Nothing has been logged yet -> idle easter-egg instead of a blank void.
            if (render.totalCount == 0) ConsoleEmptyState(settings.customArt)
            val hScroll = rememberScrollState()
            val baseStyle = TextStyle(
                fontFamily = LocalMonoFamily.current,
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
            // Precompute the WARN/ERROR strip rects once per (layout, severities,
            // palette) change rather than walking every entry + querying the
            // layout on every draw frame -- a 5000-line buffer would otherwise
            // re-measure the gutter on each scroll tick.
            val gutterRects = remember(
                layoutResult, render.lineSeverities,
                themeColors.warnAccent, themeColors.criticalAccent, verticalPaddingPx,
            ) {
                computeGutterRects(
                    layout     = layoutResult,
                    severities = render.lineSeverities,
                    warnColor  = themeColors.warnAccent,
                    errorColor = themeColors.criticalAccent,
                    yOffsetPx  = verticalPaddingPx,
                )
            }
            val gutterModifier = if (showGutter && gutterRects.isNotEmpty()) {
                Modifier.drawBehind {
                    for (r in gutterRects) {
                        drawRect(color = r.color, topLeft = Offset(0f, r.top), size = Size(gutterWidthPx, r.height))
                    }
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
            // with a Compose-rendered one painted from NxTheme.
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
                            text         = render.annotated,
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
                            // success surfaces in NxColors as currently
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
                // LaunchedEffect(render.ranges.size) clamp resets
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
            filtered       = render.filteredCount,
            total          = entries.size,
            historyOffset  = historyOffset,
            warnCount      = render.warnCount,
            errorCount     = render.errorCount,
            following      = isAtBottom,
            searchActive   = effectiveQuery.isNotBlank(),
            matchCurrent   = if (render.ranges.isNotEmpty()) currentMatch + 1 else 0,
            matchTotal     = render.ranges.size,
            searchCapped   = render.searchCapped,
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
    val colors = NxTheme.colors
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
            fontFamily = LocalMonoFamily.current,
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
                    .clip(MaterialTheme.shapes.small)
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
                    fontFamily = LocalMonoFamily.current,
                )
            }

            IconButton(onClick = onToggleWrap, modifier = Modifier.size(32.dp)) {
                Symbol(NxIcon.WrapText,
                    contentDescription = strings.consoleWrap,
                    tint = if (wrapText) colors.success else colors.textSecondary,
                )
            }
            IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                Symbol(NxIcon.Save, strings.consoleSaveToFile, tint = colors.textSecondary)
            }
            IconButton(onClick = onCopyAll, modifier = Modifier.size(32.dp)) {
                Symbol(NxIcon.ContentCopy, strings.consoleCopyAll, tint = colors.textSecondary)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Symbol(NxIcon.Delete, strings.consoleClear, tint = colors.textSecondary)
            }

            // In-window gear: quick-access menu for the persisted toggles
            // (gutter strip, timestamps) that don't belong on the main
            // toolbar bar but should be one click away.
            var gearOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { gearOpen = true }, modifier = Modifier.size(32.dp)) {
                    Symbol(NxIcon.Settings, strings.consoleSettingsLabel, tint = colors.textSecondary)
                }
                DropdownMenu(
                    expanded         = gearOpen,
                    onDismissRequest = { gearOpen = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (showGutter) strings.consoleHideGutter else strings.consoleShowGutter,
                                fontFamily = LocalMonoFamily.current,
                                fontSize   = 11.sp,
                            )
                        },
                        onClick = { onToggleGutter(); gearOpen = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (showTimestamps) strings.consoleHideTimestamps else strings.consoleShowTimestamps,
                                fontFamily = LocalMonoFamily.current,
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
            .clip(MaterialTheme.shapes.small)
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
            fontFamily = LocalMonoFamily.current,
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
    val colors = NxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = "/",
            color      = colors.textSecondary,
            fontFamily = LocalMonoFamily.current,
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
                fontFamily = LocalMonoFamily.current,
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
                        fontFamily = LocalMonoFamily.current,
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
                fontFamily = LocalMonoFamily.current,
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
                fontFamily = LocalMonoFamily.current,
                fontWeight = FontWeight.Bold,
            )
        }
        PromptButton(onClick = onPrev) {
            Text(
                text       = "<",
                color      = colors.textSecondary,
                fontSize   = 12.sp,
                lineHeight = 14.sp,
                fontFamily = LocalMonoFamily.current,
            )
        }
        PromptButton(onClick = onNext) {
            Text(
                text       = ">",
                color      = colors.textSecondary,
                fontSize   = 12.sp,
                lineHeight = 14.sp,
                fontFamily = LocalMonoFamily.current,
            )
        }
        PromptButton(onClick = onClose) {
            Symbol(icon = NxIcon.Close,
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
            .clip(MaterialTheme.shapes.small)
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
    val colors = NxTheme.colors
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
            fontFamily = LocalMonoFamily.current,
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
                fontFamily = LocalMonoFamily.current,
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
                        fontFamily = LocalMonoFamily.current,
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
    searchCapped: Boolean = false,
    onResumeFollow: () -> Unit,
) {
    val colors = NxTheme.colors
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
            fontFamily = LocalMonoFamily.current,
            fontSize   = 10.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text       = strings.consoleStatusFiltered(warnCount, errorCount),
            color      = colors.textSecondary,
            fontFamily = LocalMonoFamily.current,
            fontSize   = 10.sp,
        )

        Spacer(Modifier.weight(1f))

        if (searchActive) {
            // "+" suffix marks that matches hit MAX_SEARCH_MATCHES and the rest
            // were not highlighted -- F3 still steps through the first cap-worth.
            Text(
                text       = strings.consoleStatusMatch(matchCurrent, matchTotal) + if (searchCapped) "+" else "",
                color      = colors.textSecondary,
                fontFamily = LocalMonoFamily.current,
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
                fontFamily = LocalMonoFamily.current,
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
        return when (key) {
            Key.Escape -> { onCloseSearch(); true }
            Key.F3 if ev.isShiftPressed -> { onPrevMatch(); true }
            Key.F3 -> { onNextMatch(); true }
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

// ── Render document builder + styling ────────────────────────────────────────
// buildConsoleDoc is the structural pass: one walk over the entries emitting the
// plain text, a palette-free span layout (severity base, ERROR_MARKERS overlay,
// search overlay), the F3/n match ranges, the gutter severities, and the counts.
// styleDoc then colours those spans for a given palette. Splitting the two keeps
// a theme change off the expensive filter/regex/annotate path -- only styleDoc
// re-runs. DIVIDERs render inline as their own dimmed line.

/**
 * Idle filler for a console with no log lines yet (`totalCount == 0`) -- a friendly
 * stand-in instead of a blank panel, plus a hint to launch something. ASCII + mono
 * font so it renders in any theme without glyph gaps.
 */
@Composable
private fun ConsoleEmptyState(extraArts: List<String>) {
    val s = LocalStrings.current
    // Built-in shapes + the bundled art file + the user's Settings-managed art
    // ([extraArts] = ConsoleSettings.customArt). All three pools, one random pick.
    val arts by produceState(CONSOLE_EMPTY_ARTS + extraArts, extraArts) {
        value = runCatching {
            val fileArts = parseArtBlocks(Res.readBytes("files/console_empty_art.txt").decodeToString())
            (CONSOLE_EMPTY_ARTS + fileArts + extraArts).filter { it.isNotBlank() }
        }.getOrDefault((CONSOLE_EMPTY_ARTS + extraArts).filter { it.isNotBlank() })
    }
    // One random art per appearance -- re-rolls only when the loaded set changes.
    val art = remember(arts) { arts.random() }
    Column(
        modifier            = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Left-aligned text, centred as a block: ASCII art keeps its shape and the
        // whole picture still sits in the middle of the panel.
        Text(
            text      = art,
            // DejaVu Sans (not the mono UI font) -- it carries Braille at a uniform
            // cell; lineHeight == fontSize so the dot rows stack without gaps.
            style     = TextStyle(fontFamily = nexiraBrailleFamily(), fontSize = 16.sp, lineHeight = 16.sp),
            color     = NxTheme.colors.textSecondary.copy(alpha = 0.5f),
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text      = s.consoleEmptyHint,
            style     = MaterialTheme.typography.bodySmall,
            color     = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

// Console filler is rendered in the bundled DejaVu Sans (Braille-capable). Art is
// built from a plain pixel grid ('#' = on) and packed into Braille -- 2x4 dots per
// cell -- so each picture stays original and provably correct, with no hand-placed
// Braille code points. Add shapes as grids below; one is shown at random per show.
private fun brailleArt(rows: List<String>): String {
    val height = rows.size
    val width = rows.maxOfOrNull { it.length } ?: 0
    val grid = rows.map { it.padEnd(width) }
    fun on(x: Int, y: Int) = y in 0 until height && x in 0 until width && grid[y][x] == '#'
    // Braille dot bit per (col 0..1, row 0..3): dots 1,2,3,7 then 4,5,6,8.
    val dotBit = intArrayOf(0x01, 0x02, 0x04, 0x40, 0x08, 0x10, 0x20, 0x80)
    val out = StringBuilder()
    var cy = 0
    while (cy < height) {
        var cx = 0
        while (cx < width) {
            var bits = 0
            for (col in 0..1) for (row in 0..3) if (on(cx + col, cy + row)) bits = bits or dotBit[col * 4 + row]
            out.append('⠀' + bits)
            cx += 2
        }
        out.append('\n')
        cy += 4
    }
    return out.toString().trimEnd('\n')
}

// Parse files/console_empty_art.txt: '#' lines are comments, "---" on its own line
// separates pictures. Returns the non-blank blocks verbatim (Braille/ASCII preserved).
private fun parseArtBlocks(text: String): List<String> {
    val arts = mutableListOf<String>()
    val cur = StringBuilder()
    fun flush() { cur.toString().trim('\n').takeIf { it.isNotBlank() }?.let { arts += it }; cur.clear() }
    for (line in text.lineSequence()) {
        when {
            line.trimStart().startsWith("#") -> {}
            line.trim() == "---" -> flush()
            else -> cur.append(line).append('\n')
        }
    }
    flush()
    return arts
}

private val ART_HEART = listOf(
    " ###   ### ",
    "###########",
    "###########",
    "###########",
    " ######### ",
    "  #######  ",
    "   #####   ",
    "    ###    ",
    "     #     ",
)
private val ART_DIAMOND = listOf(
    "      #      ",
    "     ###     ",
    "    #####    ",
    "   #######   ",
    "  #########  ",
    " ########### ",
    "#############",
    " ########### ",
    "  #########  ",
    "   #######   ",
    "    #####    ",
    "     ###     ",
    "      #      ",
)
private val ART_TRIANGLE = listOf(
    "      #      ",
    "     ###     ",
    "     ###     ",
    "    #####    ",
    "   #######   ",
    "  #########  ",
    " ########### ",
    "#############",
    "#############",
)

private val ART_PLANET = listOf(
    ".....######.....",
    "....########....",
    "...##########...",
    "..############..",
    "..############..",
    "..############..",
    "..############..",
    "..############..",
    "..############..",
    "...##########...",
    "....########....",
    ".....######.....",
)
private val ART_RING = listOf(
    ".....######.....",
    "....########....",
    "...##......##...",
    "..##........##..",
    "..##........##..",
    "..##........##..",
    "..##........##..",
    "..##........##..",
    "..##........##..",
    "...##......##...",
    "....########....",
    ".....######.....",
)
private val ART_CRESCENT = listOf(
    ".....######.....",
    "....#####.......",
    "...#####........",
    "..#####.........",
    "..#####.........",
    "..#####.........",
    "..#####.........",
    "..#####.........",
    "..#####.........",
    "...#####........",
    "....#####.......",
    ".....######.....",
)
private val ART_STAR = listOf(
    ".......##........",
    ".......##........",
    "......####.......",
    ".##############..",
    "...##########....",
    "....########.....",
    ".....######......",
    ".....######......",
    ".....######......",
    "....###..###.....",
    "....#......#.....",
)

private val CONSOLE_EMPTY_ARTS: List<String> = listOf(
    brailleArt(ART_HEART),
    brailleArt(ART_DIAMOND),
    brailleArt(ART_TRIANGLE),
    brailleArt(ART_PLANET),
    brailleArt(ART_RING),
    brailleArt(ART_CRESCENT),
    brailleArt(ART_STAR),
)

internal fun buildConsoleDoc(
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
): ConsoleDoc {
    // Pre-compile the user rules once (regex rules with a bad pattern compile to
    // null and are skipped). A line is muted if it matches any enabled filter
    // rule; it takes the colour of the first enabled highlight rule it matches.
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
    // One pass for counts (over ALL entries) + the severity/query filter that
    // produces the displayed list. Severity gates first; query-narrowing only
    // when search-as-filter is on. Dividers always pass so session boundaries
    // stay visible.
    var warnCount = 0
    var errorCount = 0
    val entries = ArrayList<LogEntry>(all.size)
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
        // User mute rules hide matching lines entirely; dividers always survive so
        // session boundaries stay visible.
        if (e.type != LogType.DIVIDER && matchesFilter(e.text)) continue
        entries.add(e)
    }

    val sb = StringBuilder()
    val spans = ArrayList<DocSpan>()
    val matches = mutableListOf<IntRange>()
    val severities = mutableListOf<LineSeverity>()
    var searchCapped = false

    for ((idx, e) in entries.withIndex()) {
        val lineStart = sb.length
        val lineText: String

        if (e.type == LogType.DIVIDER) {
            lineText = e.text
            sb.append(lineText)
            spans.add(DocSpan(lineStart, sb.length, SpanRole.Divider))
        } else {
            val role = when (e.type) {
                LogType.WARN  -> SpanRole.Warn
                LogType.ERROR -> SpanRole.Error
                // DIVIDER is handled in the if-branch above; INFO is the
                // remaining reachable case.
                else          -> SpanRole.Info
            }
            lineText = if (showTimestamps) "[${e.timestamp}] ${e.text}" else e.text
            sb.append(lineText)
            spans.add(DocSpan(lineStart, sb.length, role))
            // User highlight rule wins over severity colour for the whole line;
            // marker / search overlays added below still paint over their sub-ranges.
            highlightFor(lineText)?.let { spans.add(DocSpan(lineStart, sb.length, role, it.colorHex, it.bold)) }
            if (e.type == LogType.ERROR || e.type == LogType.WARN) {
                ERROR_MARKERS.findAll(lineText).forEach { m ->
                    spans.add(DocSpan(lineStart + m.range.first, lineStart + m.range.last + 1, SpanRole.Marker))
                }
            }
        }
        severities.add(LineSeverity(lineStart, sb.length, e.type))

        // Search highlight + match-offset collection, scanning the just-appended
        // line text directly. Bounded by MAX_SEARCH_MATCHES so a broad query over a
        // huge buffer can't blow up memory; once the cap is hit the scan stops.
        if (rawQuery.isNotBlank() && matches.size < MAX_SEARCH_MATCHES) {
            val matchRanges = if (regexMode) {
                regexCompiled?.findAll(lineText)?.map { it.range }?.toList().orEmpty()
            } else {
                findAllSubstring(lineText, rawQuery)
            }
            for (range in matchRanges) {
                if (range.isEmpty()) continue
                val absStart = lineStart + range.first
                val absEnd   = lineStart + range.last + 1
                spans.add(DocSpan(absStart, absEnd, SpanRole.Search))
                // Inclusive end so the IntRange size mirrors the match span.
                matches.add(absStart until absEnd)
                if (matches.size >= MAX_SEARCH_MATCHES) { searchCapped = true; break }
            }
        }

        if (idx != entries.lastIndex) sb.append('\n')
    }

    return ConsoleDoc(
        text           = sb.toString(),
        spans          = spans,
        ranges         = matches,
        lineSeverities = severities,
        filteredCount  = entries.size,
        totalCount     = all.size,
        warnCount      = warnCount,
        errorCount     = errorCount,
        searchCapped   = searchCapped,
    )
}

// Colour the palette-free [doc] for the active [palette]. Base-line spans are
// added before their marker / search overlays (preserved by [buildConsoleDoc]'s
// emission order) so the overlay colour + weight win on the sub-range they
// cover -- a later addStyle merges over an earlier one.
internal fun styleDoc(doc: ConsoleDoc, palette: ConsolePalette): ConsoleRender {
    val annotated = buildAnnotatedString {
        append(doc.text)
        for (sp in doc.spans) {
            val style = if (sp.colorHex != null) {
                SpanStyle(color = CustomTheme.parseHexColor(sp.colorHex), fontWeight = if (sp.bold) FontWeight.Bold else null)
            } else {
                spanStyleFor(sp.role, palette)
            }
            addStyle(style, sp.start, sp.end)
        }
    }
    return ConsoleRender(
        annotated      = annotated,
        ranges         = doc.ranges,
        lineSeverities = doc.lineSeverities,
        filteredCount  = doc.filteredCount,
        totalCount     = doc.totalCount,
        warnCount      = doc.warnCount,
        errorCount     = doc.errorCount,
        searchCapped   = doc.searchCapped,
    )
}

private fun spanStyleFor(role: SpanRole, p: ConsolePalette): SpanStyle = when (role) {
    SpanRole.Divider -> SpanStyle(color = p.divider, fontWeight = FontWeight.Light)
    SpanRole.Info    -> SpanStyle(color = p.severityInfo)
    SpanRole.Warn    -> SpanStyle(color = p.severityWarn)
    SpanRole.Error   -> SpanStyle(color = p.severityError)
    SpanRole.Marker  -> SpanStyle(color = p.severityError, fontWeight = FontWeight.Bold)
    SpanRole.Search  -> SpanStyle(color = p.searchMatch, background = p.searchMatchBg, fontWeight = FontWeight.Bold)
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

// A precomputed gutter strip rect: a thin vertical bar at the left edge of a
// rendered WARN / ERROR line. Computed off the draw frame (see
// [computeGutterRects]) so the paint pass is a flat list iteration.
private class GutterRect(val top: Float, val height: Float, val color: Color)

// Map every WARN/ERROR entry's char range to its visual line extent via the
// TextLayoutResult, once per layout / severities change. runCatching guards a
// transient stale layout while the buffer catches up. INFO / DIVIDER lines get
// no bar.
private fun computeGutterRects(
    layout: TextLayoutResult?,
    severities: List<LineSeverity>,
    warnColor: Color,
    errorColor: Color,
    yOffsetPx: Float,
): List<GutterRect> {
    if (layout == null || severities.isEmpty()) return emptyList()
    val textLen = layout.layoutInput.text.length
    if (textLen == 0) return emptyList()
    val rects = ArrayList<GutterRect>()
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
            rects.add(GutterRect(yOffsetPx + top, bottom - top, color))
        }
    }
    return rects
}

private fun findAllSubstring(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var i = text.indexOf(query, ignoreCase = true)
    while (i >= 0) {
        out.add(i until i + query.length)
        // Non-overlapping (matches less / grep / IDE find semantics):
        // "aa" in "aaaa" -> hits (0..1) and (2..3), not (0..1)(1..2)(2..3).
        i = text.indexOf(query, startIndex = i + query.length, ignoreCase = true)
    }
    return out
}

