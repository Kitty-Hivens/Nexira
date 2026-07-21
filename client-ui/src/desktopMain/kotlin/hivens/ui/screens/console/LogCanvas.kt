package hivens.ui.screens.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import hivens.ui.screens.ConsolePalette
import hivens.ui.utils.LogType

// Read-only phase-1 core of the virtualized log view. Draws only the lines a
// viewport covers, from cached per-line layouts, at a pixel scroll offset. No
// selection yet (phase 3); the surrounding ConsoleContent keeps the old path until
// cut-over, so this is exercised via a dev/test harness.

internal class LogCanvasState internal constructor(val measurer: TextMeasurer) {
    val scroll = LogScrollState()
    val cache = LineLayoutCache(measurer)

    var heightIndex: HeightIndex = ConstantHeightIndex(1)
        private set

    // Horizontal offset for the no-wrap overflow; widest measured line bounds it.
    var hOffsetPx: Float = 0f
    var maxLineWidthPx: Int = 0

    // Geometry the last rebuild was keyed on, so we only rebuild when it moves.
    private var lastWrap: Boolean? = null
    private var lastLineHeight: Int = -1
    private var lastCount: Int = -1

    fun sampleLineHeightPx(base: TextStyle): Int =
        measurer.measure(text = "Ag", style = base).size.height.coerceAtLeast(1)

    /** Rebuild the height index when wrap / line height / count changes; else keep it
     *  (so measured wrap heights survive an append). */
    fun syncGeometry(wrap: Boolean, lineHeightPx: Int, count: Int) {
        val shapeChanged = wrap != lastWrap || lineHeightPx != lastLineHeight
        if (shapeChanged) {
            heightIndex = if (wrap) FenwickHeightIndex(count, lineHeightPx)
                          else ConstantHeightIndex(lineHeightPx).also { it.reset(count, lineHeightPx) }
        } else if (count != lastCount) {
            // Same shape, line count moved. Constant re-seeds in O(1). Fenwick re-seeds
            // to estimates and re-measures visible lines on the next draw; refining the
            // wrap index to preserve measured heights across an append is a phase-2
            // follow-up (today a wrap append briefly softens the scrollbar).
            heightIndex.reset(count, lineHeightPx)
        }
        lastWrap = wrap; lastLineHeight = lineHeightPx; lastCount = count
    }

    fun scrollToLine(index: Int, viewportFraction: Float = 0.33f) {
        val top = heightIndex.topOfLine(index)
        scroll.scrollTo(top - scroll.viewportPx * viewportFraction)
    }
}

@Composable
internal fun rememberLogCanvasState(): LogCanvasState {
    val measurer = rememberTextMeasurer()
    return remember { LogCanvasState(measurer) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LogCanvas(
    state: LogCanvasState,
    lines: LineModels,
    palette: ConsolePalette,
    baseStyle: TextStyle,
    wrap: Boolean,
    showGutter: Boolean,
    warnColor: Color,
    errorColor: Color,
    startPadPx: Float,
    topPadPx: Float,
    gutterWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val lineHeightPx = remember(baseStyle) { state.sampleLineHeightPx(baseStyle) }

    // A style change (theme / font / wrap / width) makes every cached layout stale.
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val effStyle = LineStyle(
        palette = palette,
        baseStyle = baseStyle,
        wrap = wrap,
        widthPx = (viewportWidthPx - startPadPx.toInt()).coerceAtLeast(0),
    )
    remember(palette, baseStyle, wrap, effStyle.widthPx) { state.cache.invalidate(); 0 }
    remember(wrap, lineHeightPx, lines) { state.syncGeometry(wrap, lineHeightPx, lines.lines.size); 0 }

    // Sticky-bottom: if the user was at the bottom, keep following as content grows.
    val wasAtBottom = state.scroll.atBottom
    remember(lines) {
        state.scroll.contentHeightPx = state.heightIndex.totalHeight
        if (wasAtBottom) state.scroll.scrollToBottom()
        0
    }

    val idx = state.heightIndex

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                viewportWidthPx = it.width
                state.scroll.viewportPx = it.height
                // Re-clamp: the offset may have been set (sticky-bottom) before the
                // viewport was known, which would over-scroll past the true maximum.
                state.scroll.scrollTo(state.scroll.offsetPx)
            }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) state.scroll.scrollBy(dy * lineHeightPx * SCROLL_LINES_PER_NOTCH)
            }
            .drawBehind {
                val offset = state.scroll.offsetPx
                val count = lines.lines.size
                if (count == 0) return@drawBehind

                val first = idx.lineAtOffset(offset.toInt())
                var i = first
                var contentBottomChanged = false

                while (i < count) {
                    val top = idx.topOfLine(i).toFloat()
                    val yOnScreen = topPadPx + top - offset
                    if (yOnScreen > size.height) break

                    val line = lines.lines[i]
                    val layout = state.cache.layoutFor(line, effStyle)
                    val h = layout.size.height
                    if (wrap && h > 0) {
                        // Correct the estimate with the measured height; totals firm up.
                        idx.setHeight(i, h)
                        contentBottomChanged = true
                    }
                    if (layout.size.width > state.maxLineWidthPx) state.maxLineWidthPx = layout.size.width

                    // Gutter severity bar, flush left, spanning the line's height.
                    if (showGutter && (line.severity == LogType.WARN || line.severity == LogType.ERROR)) {
                        drawRect(
                            color = if (line.severity == LogType.ERROR) errorColor else warnColor,
                            topLeft = Offset(0f, yOnScreen),
                            size = Size(gutterWidthPx, (if (wrap) h else lineHeightPx).toFloat()),
                        )
                    }

                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(startPadPx - state.hOffsetPx, yOnScreen),
                    )

                    i++
                }

                // Keep the scrollbar honest as wrap heights resolve.
                val total = idx.totalHeight
                if (contentBottomChanged && state.scroll.contentHeightPx != total) {
                    state.scroll.contentHeightPx = total
                }
            },
    )
}

private const val SCROLL_LINES_PER_NOTCH = 3f
