@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package hivens.ui.screens.console

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import java.awt.datatransfer.StringSelection
import java.io.File

@Composable
internal actual fun ConsoleContextMenu(entries: () -> List<ConsoleMenuEntry>, content: @Composable () -> Unit) {
    ContextMenuArea(
        items = { entries().map { ContextMenuItem(it.label, it.action) } },
        content = content,
    )
}

@Composable
internal actual fun BoxScope.ConsoleScrollbars(canvasState: LogCanvasState, wrap: Boolean, hostWidthPx: () -> Int) {
    // Console-local scrollbar style: the default is near-invisible against
    // the console background, so bump thickness + alpha.
    val inkQuiet = NxInk.quiet
    val scrollbarStyle = ScrollbarStyle(
        minimalHeight       = 24.dp,
        thickness           = 8.dp,
        shape               = RoundedCornerShape(4.dp),
        hoverDurationMillis = 250,
        unhoverColor        = NxColor.wash(inkQuiet, 0.40f),
        hoverColor          = NxColor.wash(inkQuiet, 0.75f),
    )
    VerticalScrollbar(
        adapter  = canvasState.scroll.scrollbarAdapter().asScrollbarAdapter(),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        style    = scrollbarStyle,
    )
    if (!wrap) {
        HorizontalScrollbar(
            adapter  = canvasState.horizontalScrollbarAdapter(hostWidthPx).asScrollbarAdapter(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            style    = scrollbarStyle,
        )
    }
}

private fun ScrollMetrics.asScrollbarAdapter(): ScrollbarAdapter = object : ScrollbarAdapter {
    override val scrollOffset: Double get() = this@asScrollbarAdapter.scrollOffset
    override val contentSize: Double get() = this@asScrollbarAdapter.contentSize
    override val viewportSize: Double get() = this@asScrollbarAdapter.viewportSize
    override suspend fun scrollTo(scrollOffset: Double) = this@asScrollbarAdapter.scrollTo(scrollOffset)
}

internal actual suspend fun deliverConsoleExport(file: File): Boolean = true

internal actual suspend fun Clipboard.copyPlainText(text: String) = setClipEntry(ClipEntry(StringSelection(text)))
