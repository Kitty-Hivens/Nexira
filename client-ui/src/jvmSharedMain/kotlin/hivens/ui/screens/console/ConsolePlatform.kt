package hivens.ui.screens.console

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.Clipboard

/**
 * What a scrollbar needs to know about a scrollable the console draws itself, the
 * shape of a desktop scrollbar adapter without its type, so the console state does
 * not name a desktop class.
 */
internal interface ScrollMetrics {
    val scrollOffset: Double
    val contentSize: Double
    val viewportSize: Double
    suspend fun scrollTo(scrollOffset: Double)
}

/** One entry of the menu a pointer opens on the log. */
internal class ConsoleMenuEntry(val label: String, val action: () -> Unit)

/**
 * Wraps the log in the menu a right click opens. A host with no secondary button
 * draws the log alone.
 */
@Composable
internal expect fun ConsoleContextMenu(entries: () -> List<ConsoleMenuEntry>, content: @Composable () -> Unit)

/**
 * The bars along the right and bottom edges of the log: vertical always, horizontal
 * while lines are not wrapped. A touch host scrolls by finger and draws none.
 */
@Composable
internal expect fun BoxScope.ConsoleScrollbars(canvasState: LogCanvasState, wrap: Boolean, hostWidthPx: () -> Int)

/** Puts [text] on the clipboard as plain text. */
internal expect suspend fun Clipboard.copyPlainText(text: String)

/**
 * Calls [onScroll] with the vertical delta of each wheel notch over this element.
 * Positive is wheel down on most platforms.
 */
internal fun Modifier.onWheelScroll(onScroll: (Float) -> Unit): Modifier = pointerInput(onScroll) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) {
                onScroll(event.changes.firstOrNull()?.scrollDelta?.y ?: 0f)
            }
        }
    }
}
