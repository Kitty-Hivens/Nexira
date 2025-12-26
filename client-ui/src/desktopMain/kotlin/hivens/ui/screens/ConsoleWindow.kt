package hivens.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

@Composable
fun ConsoleWindow(onClose: () -> Unit) {
    val windowState = rememberWindowState(width = 900.dp, height = 600.dp)

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Debug Console",
        alwaysOnTop = false
    ) {
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()

        val logsCopy = remember(GameConsoleService.logs.size, GameConsoleService.logs.lastOrNull()) {
            GameConsoleService.logs.toList()
        }

        MaterialTheme(colors = darkColors()) {
            Surface(color = Color(0xFF121212)) {
                Column(Modifier.fillMaxSize()) {
                    // Toolbar
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Game Output (${logsCopy.size})",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )

                        Row {
                            // Кнопка копирования
                            IconButton(onClick = {
                                val fullLog = logsCopy.joinToString("\n") {
                                    "[${it.timestamp}] ${it.text}"
                                }
                                scope.launch {
                                    val selection = StringSelection(fullLog)
                                    clipboard.setClipEntry(ClipEntry(selection))
                                }
                            }) {
                                Icon(Icons.Default.ContentCopy, "Copy All", tint = Color.Gray)
                            }

                            // Кнопка очистки
                            IconButton(onClick = { GameConsoleService.clear() }) {
                                Icon(Icons.Default.Delete, "Clear", tint = Color.Gray)
                            }
                        }
                    }

                    // Logs Area
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        val listState = rememberLazyListState()

                        // Автоскролл
                        LaunchedEffect(logsCopy.size) {
                            if (logsCopy.isNotEmpty()) {
                                listState.scrollToItem(logsCopy.lastIndex)
                            }
                        }

                        SelectionContainer {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(logsCopy) { log ->
                                    LogLine(log)
                                }
                            }
                        }

                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogLine(log: hivens.ui.utils.LogEntry) {
    val color = when (log.type) {
        LogType.INFO -> Color(0xFFCCCCCC)
        LogType.WARN -> Color(0xFFFFD54F)
        LogType.ERROR -> Color(0xFFEF5350)
    }

    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "[${log.timestamp}] ",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Text(
            text = log.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}
