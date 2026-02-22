package hivens.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

// ── Keyword highlight rules ──────────────────────────────────────────────────

private data class HighlightRule(val regex: Regex, val color: Color)

private val HIGHLIGHT_RULES = listOf(
    // Exceptions & errors
    HighlightRule(Regex("(Exception|Error|FATAL|SEVERE|Caused by:|at )"), Color(0xFFEF5350)),
    // null / NullPointer
    HighlightRule(Regex("\\bnull\\b"), Color(0xFFFF7043)),
    // WARN keywords
    HighlightRule(Regex("\\b(WARN|WARNING|Deprecated)\\b"), Color(0xFFFFD54F)),
    // Fully-qualified class names  (com.foo.Bar / net.minecraft.X)
    HighlightRule(Regex("[a-z][a-z0-9_]+(\\.[a-z][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_]*"), Color(0xFF90A4AE)),
    // Numbers
    HighlightRule(Regex("\\b\\d+(\\.\\d+)?\\b"), Color(0xFF4DD0E1)),
)

private val TIMESTAMP_REGEX = Regex("^\\[\\d{2}:\\d{2}:\\d{2}].*")

// ── Font size options ────────────────────────────────────────────────────────

private val FONT_SIZES = listOf(11, 12, 14)

// ── Main composable ──────────────────────────────────────────────────────────

@Composable
fun ConsoleWindow(isDarkTheme: Boolean, onClose: () -> Unit) {
    val windowState = rememberWindowState(width = 960.dp, height = 620.dp)

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Debug Console",
        alwaysOnTop = false,
        transparent = false,
        undecorated = false
    ) {
        val bg        = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF5F5F5)
        val toolbarBg = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFE0E0E0)
        val textColor = if (isDarkTheme) Color(0xFFCCCCCC) else Color(0xFF212121)

        MaterialTheme(colors = if (isDarkTheme) darkColors() else lightColors()) {
            Surface(modifier = Modifier.fillMaxSize(), color = bg) {
                ConsoleContent(
                    bg = bg,
                    toolbarBg = toolbarBg,
                    textColor = textColor,
                    isDarkTheme = isDarkTheme
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsoleContent(
    bg: Color,
    toolbarBg: Color,
    textColor: Color,
    isDarkTheme: Boolean
) {
    val clipboard = LocalClipboard.current
    val scope     = rememberCoroutineScope()

    // ── State ──────────────────────────────────────────────────────────────
    var searchQuery  by remember { mutableStateOf("") }
    var wrapText     by remember { mutableStateOf(true) }
    var fontSize     by remember { mutableStateOf(12) }
    var showFontMenu by remember { mutableStateOf(false) }

    var filterInfo  by remember { mutableStateOf(true) }
    var filterWarn  by remember { mutableStateOf(true) }
    var filterError by remember { mutableStateOf(true) }

    val logsCopy = remember(GameConsoleService.logs.size, GameConsoleService.logs.lastOrNull()) {
        GameConsoleService.logs.toList()
    }

    val filtered = remember(logsCopy, searchQuery, filterInfo, filterWarn, filterError) {
        logsCopy.filter { entry ->
            val typeOk = when (entry.type) {
                LogType.INFO    -> filterInfo
                LogType.WARN    -> filterWarn
                LogType.ERROR   -> filterError
                LogType.DIVIDER -> true
            }
            val searchOk = searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true)
            typeOk && searchOk
        }
    }

    val warnCount  = remember(logsCopy) { logsCopy.count { it.type == LogType.WARN } }
    val errorCount = remember(logsCopy) { logsCopy.count { it.type == LogType.ERROR } }

    // ── Auto-scroll with pause ─────────────────────────────────────────────
    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= filtered.size - 2
        }
    }

    LaunchedEffect(filtered.size) {
        if (isAtBottom && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    // ── Horizontal scroll for no-wrap mode ───────────────────────────────
    val hScroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {

        // ══ Toolbar ═══════════════════════════════════════════════════════
        Column(Modifier.fillMaxWidth().background(toolbarBg)) {

            // Row 1: title + filter toggles + actions
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title + count
                Text(
                    "Game Output (${filtered.size}/${logsCopy.size})",
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Filter toggles with counters
                    FilterChip("INFO", filterInfo, Color(0xFF4CAF50)) { filterInfo = it }
                    Spacer(Modifier.width(4.dp))
                    FilterChip("⚠ $warnCount", filterWarn, Color(0xFFFFD54F)) { filterWarn = it }
                    Spacer(Modifier.width(4.dp))
                    FilterChip("✕ $errorCount", filterError, Color(0xFFEF5350)) { filterError = it }

                    Spacer(Modifier.width(8.dp))
                    Divider(modifier = Modifier.height(20.dp).width(1.dp), color = textColor.copy(alpha = 0.2f))
                    Spacer(Modifier.width(8.dp))

                    // Font size
                    Box {
                        TextButton(onClick = { showFontMenu = true }) {
                            Text("${fontSize}px", color = textColor.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                        DropdownMenu(
                            expanded = showFontMenu,
                            onDismissRequest = { showFontMenu = false }
                        ) {
                            FONT_SIZES.forEach { size ->
                                DropdownMenuItem(onClick = { fontSize = size; showFontMenu = false }) {
                                    Text("${size}px", fontWeight = if (size == fontSize) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }

                    // Wrap toggle
                    IconButton(onClick = { wrapText = !wrapText }) {
                        Icon(
                            Icons.AutoMirrored.Filled.WrapText,
                            "Wrap",
                            tint = if (wrapText) Color(0xFF4CAF50) else textColor.copy(alpha = 0.4f)
                        )
                    }

                    // Save to file
                    IconButton(onClick = { GameConsoleService.saveToFile() }) {
                        Icon(Icons.Default.Save, "Save to file", tint = textColor.copy(alpha = 0.7f))
                    }

                    // Copy all
                    IconButton(onClick = {
                        val text = logsCopy.joinToString("\n") { entry ->
                            if (entry.type == LogType.DIVIDER) entry.text
                            else if (entry.text.matches(TIMESTAMP_REGEX)) entry.text
                            else "[${entry.timestamp}] ${entry.text}"
                        }
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(StringSelection(text)))
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy All", tint = textColor.copy(alpha = 0.7f))
                    }

                    // Clear
                    IconButton(onClick = { GameConsoleService.clear() }) {
                        Icon(Icons.Default.Delete, "Clear", tint = textColor.copy(alpha = 0.7f))
                    }
                }
            }

            // Row 2: Search bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = textColor.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(textColor),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text("Search...", color = textColor.copy(alpha = 0.3f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text("✕", color = textColor.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                }
            }
        }

        // ══ Log area ═══════════════════════════════════════════════════════
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
            val logModifier = if (wrapText) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().horizontalScroll(hScroll)

            SelectionContainer {
                LazyColumn(state = listState, modifier = logModifier) {
                    items(filtered) { log ->
                        LogLine(
                            log = log,
                            fontSize = fontSize,
                            searchQuery = searchQuery,
                            wrapText = wrapText,
                            isDarkTheme = isDarkTheme,
                            onCopy = { text ->
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(StringSelection(text)))
                                }
                            }
                        )
                    }
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )

            if (!wrapText) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(hScroll),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                )
            }
        }

        // ══ Status bar ══════════════════════════════════════════════════════
        if (!isAtBottom && filtered.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().background(Color(0xFF1565C0).copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                TextButton(
                    onClick = { scope.launch { listState.scrollToItem(filtered.lastIndex) } },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text("↓ Jump to bottom", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

// ── Filter chip ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun FilterChip(label: String, active: Boolean, activeColor: Color, onToggle: (Boolean) -> Unit) {
    val bg = if (active) activeColor.copy(alpha = 0.2f) else Color.Transparent
    val border = if (active) activeColor.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)
    val textC = if (active) activeColor else Color.Gray

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        onClick = { onToggle(!active) }
    ) {
        Text(
            label,
            color = textC,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ── Log line ─────────────────────────────────────────────────────────────────

@Composable
private fun LogLine(
    log: LogEntry,
    fontSize: Int,
    searchQuery: String,
    wrapText: Boolean,
    isDarkTheme: Boolean,
    onCopy: (String) -> Unit
) {
    if (log.type == LogType.DIVIDER) {
        DividerLine(log.text, isDarkTheme)
        return
    }

    val baseColor = when (log.type) {
        LogType.INFO  -> if (isDarkTheme) Color(0xFFCCCCCC) else Color(0xFF212121)
        LogType.WARN  -> Color(0xFFFFD54F)
        LogType.ERROR -> Color(0xFFEF5350)
        //LogType.DIVIDER -> Color.Transparent
    }

    val hasOwnTimestamp = log.text.matches(TIMESTAMP_REGEX)
    val fullText = if (hasOwnTimestamp) log.text else "[${log.timestamp}] ${log.text}"

    val annotated = buildHighlightedText(fullText, searchQuery, baseColor, log.type)

    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(fullText) {
                detectTapGestures(
                    onDoubleTap = { onCopy(fullText) },
                    onLongPress = { onCopy(fullText) }
                )
            }
            .padding(vertical = 1.dp, horizontal = 8.dp)
    ) {
        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            softWrap = wrapText
        )
    }
}

@Composable
private fun DividerLine(text: String, isDarkTheme: Boolean) {
    val color = if (isDarkTheme) Color(0xFF444444) else Color(0xFFBBBBBB)
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Divider(color = color.copy(alpha = 0.4f))
        Text(
            text = " $text ",
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.background(if (isDarkTheme) Color(0xFF121212) else Color(0xFFF5F5F5))
        )
    }
}

// ── Keyword highlight ────────────────────────────────────────────────────────

private fun buildHighlightedText(
    text: String,
    searchQuery: String,
    baseColor: Color,
    type: LogType
): AnnotatedString {
    return buildAnnotatedString {
        // First pass: apply keyword highlights on top of base color
        var cursor = 0

        // Collect all match ranges with their colors
        data class Match(val range: IntRange, val color: Color, val priority: Int)

        val matches = mutableListOf<Match>()

        // Only apply keyword highlights for INFO lines (WARN/ERROR already have base color)
        if (type == LogType.INFO) {
            HIGHLIGHT_RULES.forEachIndexed { idx, rule ->
                rule.regex.findAll(text).forEach { result ->
                    matches.add(Match(result.range, rule.color, idx))
                }
            }
        }

        // Search highlight on top of everything
        if (searchQuery.isNotBlank()) {
            val lower = text.lowercase()
            val queryLower = searchQuery.lowercase()
            var start = lower.indexOf(queryLower)
            while (start != -1) {
                matches.add(Match(start until start + searchQuery.length, Color(0xFFFFEB3B), -1))
                start = lower.indexOf(queryLower, start + 1)
            }
        }

        // Sort by start, priority (search wins over keywords)
        val sorted = matches.sortedWith(compareBy({ it.range.first }, { it.priority }))

        // Merge and apply
        cursor = 0
        val used = mutableListOf<Match>()

        sorted.forEach { match ->
            if (match.range.first >= cursor) {
                withStyle(SpanStyle(color = baseColor)) {
                    append(text.substring(cursor, match.range.first))
                }
                withStyle(SpanStyle(
                    color = if (match.priority == -1) Color(0xFF212121) else match.color,
                    background = if (match.priority == -1) Color(0xFFFFEB3B) else Color.Unspecified,
                    fontWeight = if (match.priority == -1) FontWeight.Bold else FontWeight.Normal
                )) {
                    append(text.substring(match.range.first, match.range.last + 1))
                }
                cursor = match.range.last + 1
                used.add(match)
            }
        }

        if (cursor < text.length) {
            withStyle(SpanStyle(color = baseColor)) {
                append(text.substring(cursor))
            }
        }
    }
}
