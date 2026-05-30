package hivens.ui.screens

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
private data class MatchIndex(
    val annotated:    AnnotatedString,
    val offsets:      List<Int>,           // char offsets of every match in annotated
    val queryLength:  Int,
)

// ── Main composable ─────────────────────────────────────────────────────────

@Composable
fun ConsoleWindow(
    isDarkTheme: Boolean,
    onClose: () -> Unit,
    customTheme: CustomTheme? = null,
    style: StyleSpec = CelestiaStyle,
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
                ConsoleContent()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsoleContent() {
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
    var searchQuery     by remember { mutableStateOf("") }
    var regexMode       by remember { mutableStateOf(false) }
    var wrapText        by remember { mutableStateOf(true) }
    var fontSize        by remember { mutableIntStateOf(12) }
    var filterInfo      by remember { mutableStateOf(true) }
    var filterWarn      by remember { mutableStateOf(true) }
    var filterError     by remember { mutableStateOf(true) }
    var searchOpen      by remember { mutableStateOf(false) }
    var searchHasFocus  by remember { mutableStateOf(false) }
    var pendingSearchFocus by remember { mutableStateOf(false) }
    var currentMatch    by remember { mutableIntStateOf(0) }
    var copiedFlash     by remember { mutableStateOf(false) }
    var selection       by remember { mutableStateOf(TextRange.Zero) }
    var layoutResult    by remember { mutableStateOf<TextLayoutResult?>(null) }

    val scrollState     = rememberScrollState()
    val searchFocus     = remember { FocusRequester() }
    val logFocus        = remember { FocusRequester() }

    // ── Frame-bounded log coalescer ────────────────────────────────────────
    // Modded MC startup floods 5k+ lines in ~2s. Rebuilding the whole
    // AnnotatedString per append would jank. snapshotFlow + conflate +
    // distinctUntilChanged keeps rebuilds to one per frame at most.
    var logTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { gameConsole.logs.size }
            .conflate()
            .distinctUntilChanged()
            .collect { logTick = it }
    }
    val logsCopy = remember(logTick) { gameConsole.logs.toList() }

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
    val filtered = remember(logsCopy, filterInfo, filterWarn, filterError) {
        logsCopy.filter { entry ->
            when (entry.type) {
                LogType.INFO    -> filterInfo
                LogType.WARN    -> filterWarn
                LogType.ERROR   -> filterError
                LogType.DIVIDER -> true
            }
        }
    }

    val warnCount  = remember(logsCopy) { logsCopy.count { it.type == LogType.WARN } }
    val errorCount = remember(logsCopy) { logsCopy.count { it.type == LogType.ERROR } }

    val matchIndex = remember(filtered, effectiveQuery, regexMode, searchRegex, palette) {
        buildConsoleAnnotated(filtered, effectiveQuery, regexMode, searchRegex, palette)
    }

    // Clamp current match index when the match set shrinks past it.
    LaunchedEffect(matchIndex.offsets.size) {
        if (currentMatch >= matchIndex.offsets.size) currentMatch = 0
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

    // ── Match jumping ──────────────────────────────────────────────────────
    // The layout result may be from the PREVIOUS frame's BasicTextField measure
    // while matchIndex.offsets references char positions in the JUST-rebuilt
    // annotated string. Under flood, an offset can exceed the old layout's
    // text length -- MultiParagraph.getLineForOffset would throw. Guard on
    // text length first, then runCatching the layout call so a transient
    // out-of-range only loses one F3 press instead of crashing.
    fun scrollToMatch(idx: Int) {
        val layout = layoutResult ?: return
        if (idx !in matchIndex.offsets.indices) return
        val offset = matchIndex.offsets[idx]
        if (offset >= layout.layoutInput.text.length) return
        val line = runCatching { layout.getLineForOffset(offset) }.getOrNull() ?: return
        val top = runCatching { layout.getLineTop(line).toInt() }.getOrNull() ?: return
        val target = (top - scrollState.viewportSize / 3).coerceAtLeast(0)
        scope.launch { scrollState.animateScrollTo(target) }
        val endOffset = (offset + matchIndex.queryLength).coerceAtMost(matchIndex.annotated.length)
        selection = TextRange(offset, endOffset)
    }

    fun jumpNext() {
        if (matchIndex.offsets.isEmpty()) return
        currentMatch = (currentMatch + 1) % matchIndex.offsets.size
        scrollToMatch(currentMatch)
    }

    fun jumpPrev() {
        if (matchIndex.offsets.isEmpty()) return
        currentMatch = if (currentMatch == 0) matchIndex.offsets.lastIndex
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
    PuppetToggle("console.wrap",        wrapText)    { wrapText = it }
    PuppetToggle("console.regexMode",   regexMode)   { regexMode = it }
    PuppetToggle("console.searchOpen",  searchOpen)  { if (it) openSearch() else closeSearch() }
    PuppetField ("console.search", searchQuery)      { searchQuery = it }
    PuppetClick ("console.clearSearch", enabled = searchQuery.isNotEmpty()) { searchQuery = "" }
    PuppetClick ("console.saveToFile")   { gameConsole.saveToFile() }
    PuppetClick ("console.copyAll")      { copyAll() }
    PuppetClick ("console.clear")        { gameConsole.clear() }
    PuppetClick ("console.jumpToBottom", enabled = filtered.isNotEmpty()) {
        scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
    }
    PuppetClick ("console.matchNext",    enabled = matchIndex.offsets.isNotEmpty()) { jumpNext() }
    PuppetClick ("console.matchPrev",    enabled = matchIndex.offsets.isNotEmpty()) { jumpPrev() }
    FONT_SIZES.forEach { sz ->
        PuppetClick("console.fontSize.$sz") { fontSize = sz }
    }

    // ── Root layout + key handler ───────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(
                    ev          = ev,
                    searchFocus = searchHasFocus,
                    matchCount  = matchIndex.offsets.size,
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
            onToggleWrap  = { wrapText = !wrapText },
            fontSize      = fontSize,
            onFontSize    = { fontSize = it },
            onCopyAll     = { copyAll() },
            onSave        = { gameConsole.saveToFile() },
            onClear       = { gameConsole.clear() },
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

            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                val fieldModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .focusRequester(logFocus)

                BasicTextField(
                    value         = textFieldValue,
                    onValueChange = { tfv -> selection = tfv.selection },
                    readOnly      = true,
                    textStyle     = baseStyle,
                    cursorBrush   = SolidColor(themeColors.textPrimary),
                    onTextLayout  = { layoutResult = it },
                    modifier      = if (wrapText) fieldModifier
                                    else fieldModifier.horizontalScroll(hScroll),
                )
            }

            VerticalScrollbar(
                adapter  = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
            if (!wrapText) {
                HorizontalScrollbar(
                    adapter  = rememberScrollbarAdapter(hScroll),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                )
            }

            // Ephemeral "copied" overlay. Plain conditional rendering for
            // Slice A; Slice B will wrap in a Crossfade / AnimatedVisibility
            // once the wider visual refresh lands.
            if (copiedFlash) {
                Surface(
                    color = themeColors.success.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                ) {
                    Text(
                        text     = s.consoleCopied,
                        // White text reads against both light- and dark-success
                        // surfaces in CelestiaColors as currently defined; revisit
                        // in Slice 6 customization if a contrast pairing becomes
                        // necessary under user-supplied overrides.
                        color    = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ── Footer: search prompt + status ─────────────────────────────────
        HorizontalDivider(thickness = 1.dp, color = themeColors.outline.copy(alpha = 0.4f))

        if (searchOpen) {
            SearchPrompt(
                query          = searchQuery,
                onQueryChange  = { searchQuery = it; currentMatch = 0 },
                regexMode      = regexMode,
                regexValid     = !regexMode || searchQuery.isBlank() || searchRegex != null,
                onToggleRegex  = { regexMode = !regexMode },
                onClose        = { closeSearch() },
                focusRequester = searchFocus,
                onFocusChanged = { searchHasFocus = it },
                strings        = s,
                onNext         = ::jumpNext,
                onPrev         = ::jumpPrev,
            )
        }

        StatusFooter(
            strings        = s,
            filtered       = filtered.size,
            total          = logsCopy.size,
            warnCount      = warnCount,
            errorCount     = errorCount,
            following      = isAtBottom,
            searchActive   = effectiveQuery.isNotBlank(),
            matchCurrent   = if (matchIndex.offsets.isNotEmpty()) currentMatch + 1 else 0,
            matchTotal     = matchIndex.offsets.size,
            onResumeFollow = {
                scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
            },
        )
    }
}

// ── Toolbar ─────────────────────────────────────────────────────────────────

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
    onCopyAll: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = CelestiaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
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

            // Font size cycle: tap to advance through FONT_SIZES.
            TextButton(onClick = {
                val idx = FONT_SIZES.indexOf(fontSize).takeIf { it >= 0 } ?: 0
                onFontSize(FONT_SIZES[(idx + 1) % FONT_SIZES.size])
            }) {
                Text(
                    text       = "${fontSize}px",
                    color      = colors.textSecondary,
                    fontSize   = 11.sp,
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
        }
    }
}

// Flat severity toggle: no Material FilterChip border, a 2dp left accent
// when active, monochrome when not. Stays terse in the toolbar at low font
// sizes; Slice B may refine the visual but the API stays the same.
@Composable
private fun SeverityToggle(
    label: String,
    active: Boolean,
    accent: Color,
    count: Int?,
    onToggle: (Boolean) -> Unit,
) {
    val text = if (count != null && count > 0) "$label $count" else label
    TextButton(
        onClick = { onToggle(!active) },
        modifier = Modifier.height(24.dp),
    ) {
        Text(
            text       = text,
            color      = if (active) accent else accent.copy(alpha = 0.35f),
            fontSize   = 10.sp,
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
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
        TextButton(onClick = onToggleRegex, modifier = Modifier.height(24.dp)) {
            Text(
                text       = ".*",
                color      = regexTint,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        TextButton(onClick = onPrev, modifier = Modifier.height(24.dp)) {
            Text("<", color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = onNext, modifier = Modifier.height(24.dp)) {
            Text(">", color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = null,
                tint               = colors.textSecondary,
                modifier           = Modifier.size(14.dp),
            )
        }
    }
}

// ── Status footer (always visible) ──────────────────────────────────────────

@Composable
private fun StatusFooter(
    strings: AppStrings,
    filtered: Int,
    total: Int,
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
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = strings.consoleStatusLines(filtered, total),
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

    // Log-area-focused chords.
    return when {
        // Open search prompt.
        key == Key.F && ev.isCtrlPressed -> { onOpenSearch(); true }
        key == Key.Slash                 -> { onOpenSearch(); true }

        // Match nav (when there are matches).
        matchCount > 0 && key == Key.F3 && ev.isShiftPressed -> { onPrevMatch(); true }
        matchCount > 0 && key == Key.F3                       -> { onNextMatch(); true }
        matchCount > 0 && key == Key.N && ev.isShiftPressed   -> { onPrevMatch(); true }
        matchCount > 0 && key == Key.N                        -> { onNextMatch(); true }

        // Scroll anchors.
        key == Key.MoveHome && ev.isCtrlPressed  -> { onScrollTop(); true }
        key == Key.MoveEnd  && ev.isCtrlPressed  -> { onScrollBottom(); true }
        key == Key.G        && ev.isShiftPressed -> { onScrollBottom(); true }   // G
        key == Key.G                             -> { onScrollTop(); true }      // g

        // Pagewise scroll.
        key == Key.PageUp                     -> { onPageUp(); true }
        key == Key.PageDown                   -> { onPageDown(); true }
        key == Key.U && ev.isCtrlPressed      -> { onPageUp(); true }
        key == Key.D && ev.isCtrlPressed      -> { onPageDown(); true }

        // Linewise scroll.
        key == Key.K || key == Key.DirectionUp   -> { onLineUp(); true }
        key == Key.J || key == Key.DirectionDown -> { onLineDown(); true }

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
): MatchIndex {
    val offsets = mutableListOf<Int>()
    val query = rawQuery
    val queryLen = query.length

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
                lineText = "[${e.timestamp}] ${e.text}"
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

            // Search highlight + match-offset collection, scanning the
            // just-appended line text directly (no builder readback).
            if (query.isNotBlank()) {
                val matchRanges = if (regexMode) {
                    regexCompiled?.findAll(lineText)?.map { it.range }?.toList().orEmpty()
                } else {
                    findAllSubstring(lineText, query, ignoreCase = true)
                }
                matchRanges.forEach { range ->
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
                    offsets.add(absStart)
                }
            }

            if (idx != entries.lastIndex) append('\n')
        }
    }

    return MatchIndex(
        annotated   = annotated,
        offsets     = offsets,
        queryLength = if (regexMode) 0 else queryLen,
    )
}

private fun findAllSubstring(text: String, query: String, ignoreCase: Boolean): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var i = text.indexOf(query, ignoreCase = ignoreCase)
    while (i >= 0) {
        out.add(i until i + query.length)
        i = text.indexOf(query, startIndex = i + 1, ignoreCase = ignoreCase)
    }
    return out
}

