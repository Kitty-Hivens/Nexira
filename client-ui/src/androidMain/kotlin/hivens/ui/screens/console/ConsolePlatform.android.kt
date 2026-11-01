package hivens.ui.screens.console

import android.content.ClipData
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

// A touch host has no secondary button, so the log is drawn without the menu; copying
// is the toolbar's job there.
@Composable
internal actual fun ConsoleContextMenu(entries: () -> List<ConsoleMenuEntry>, content: @Composable () -> Unit) = content()

// The finger scrolls the log itself, so no bars.
@Composable
internal actual fun BoxScope.ConsoleScrollbars(canvasState: LogCanvasState, wrap: Boolean, hostWidthPx: () -> Int) = Unit

internal actual suspend fun Clipboard.copyPlainText(text: String) = setClipEntry(ClipEntry(ClipData.newPlainText("", text)))
