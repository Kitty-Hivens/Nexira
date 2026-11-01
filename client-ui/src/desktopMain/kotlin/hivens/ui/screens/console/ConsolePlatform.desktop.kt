@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package hivens.ui.screens.console

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import java.awt.datatransfer.StringSelection

@Composable
internal actual fun ConsoleContextMenu(entries: () -> List<ConsoleMenuEntry>, content: @Composable () -> Unit) {
    // Themed right-click popup (Compose-drawn, not the dated Swing default),
    // cut from the top step the way a popup is.
    val colours = NxTheme.colours
    val menuRepresentation = remember(colours) {
        val top = colours.step(colours.topStep)
        DefaultContextMenuRepresentation(
            backgroundColor = top,
            textColor       = colours.inkMain(top),
            itemHoverColor  = lerp(top, colours.lead, 0.14f),
        )
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides menuRepresentation) {
        ContextMenuArea(
            items = { entries().map { ContextMenuItem(it.label, it.action) } },
            content = content,
        )
    }
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

internal actual suspend fun Clipboard.copyPlainText(text: String) = setClipEntry(ClipEntry(StringSelection(text)))
