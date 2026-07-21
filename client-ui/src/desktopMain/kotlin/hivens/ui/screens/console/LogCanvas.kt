package hivens.ui.screens.console

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import hivens.ui.screens.ConsolePalette
import hivens.ui.utils.LogType

// Virtualized log view. Draws only the lines a viewport covers, from cached per-line
// layouts, at a pixel scroll offset -- so append and scroll are O(visible), never
// O(buffer). Owns its own selection (SelectionContainer can't span un-composed
// lines) and horizontal offset for no-wrap overflow. UX parity with the old single
// Text: same gutter, same wheel/scroll feel, same selection + copy.

internal class LogCanvasState internal constructor(val measurer: TextMeasurer) {
    val scroll = LogScrollState()
    val cache = LineLayoutCache(measurer)

    var heightIndex: HeightIndex = ConstantHeightIndex(1)
        private set

    // Horizontal offset for the no-wrap overflow; widest measured line bounds it.
    var hOffsetPx by mutableFloatStateOf(0f)
    var maxLineWidthPx by mutableIntStateOf(0)

    private var lastWrap: Boolean? = null
    private var lastLineHeight: Int = -1
    private var lastCount: Int = -1

    fun sampleLineHeightPx(base: TextStyle): Int =
        measurer.measure(text = "Ag", style = base).size.height.coerceAtLeast(1)

    fun syncGeometry(wrap: Boolean, lineHeightPx: Int, count: Int) {
        val shapeChanged = wrap != lastWrap || lineHeightPx != lastLineHeight
        if (shapeChanged) {
            heightIndex = if (wrap) FenwickHeightIndex(count, lineHeightPx)
                          else ConstantHeightIndex(lineHeightPx).also { it.reset(count, lineHeightPx) }
        } else if (count != lastCount) {
            // Same shape, line count moved. Constant re-seeds in O(1); Fenwick re-seeds
            // to estimates and re-measures visible lines on the next draw (a wrap append
            // briefly softens the scrollbar -- preserving measured heights across appends
            // is a later refinement).
            heightIndex.reset(count, lineHeightPx)
        }
        lastWrap = wrap; lastLineHeight = lineHeightPx; lastCount = count
    }

    fun scrollToLine(index: Int, viewportFraction: Float = 0.33f) {
        val top = heightIndex.topOfLine(index)
        scroll.scrollTo(top - scroll.viewportPx * viewportFraction)
    }

    /** Pointer canvas coords -> document position, via the cached layout of the line
     *  the pointer is over. */
    fun hitTest(x: Float, y: Float, lines: LineModels, style: LineStyle, topPadPx: Float, startPadPx: Float): DocPos {
        if (lines.lines.isEmpty()) return DocPos(0, 0)
        val contentY = (y - topPadPx + scroll.offsetPx).coerceAtLeast(0f)
        val line = heightIndex.lineAtOffset(contentY.toInt()).coerceIn(0, lines.lines.lastIndex)
        val lm = lines.lines[line]
        val layout = cache.layoutFor(lm, style)
        val localX = x - startPadPx + hOffsetPx
        val localY = contentY - heightIndex.topOfLine(line)
        val off = layout.getOffsetForPosition(Offset(localX, localY)).coerceIn(0, lm.text.length)
        return DocPos(line, off)
    }

    fun maxHOffset(viewportWidthPx: Int): Float = (maxLineWidthPx - viewportWidthPx).coerceAtLeast(0).toFloat()

    /** Scrollbar adapter over the horizontal offset for the no-wrap overflow. */
    fun horizontalScrollbarAdapter(viewportWidthPx: () -> Int): ScrollbarAdapter = object : ScrollbarAdapter {
        override val scrollOffset: Double get() = hOffsetPx.toDouble()
        override val contentSize: Double get() = maxLineWidthPx.toDouble()
        override val viewportSize: Double get() = viewportWidthPx().toDouble()
        override suspend fun scrollTo(scrollOffset: Double) {
            hOffsetPx = scrollOffset.toFloat().coerceIn(0f, maxHOffset(viewportWidthPx()))
        }
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
    selection: LogSelection,
    palette: ConsolePalette,
    baseStyle: TextStyle,
    wrap: Boolean,
    showGutter: Boolean,
    warnColor: Color,
    errorColor: Color,
    selectionColor: Color,
    startPadPx: Float,
    topPadPx: Float,
    gutterWidthPx: Float,
    onViewportWidth: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lineHeightPx = remember(baseStyle) { state.sampleLineHeightPx(baseStyle) }

    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val effStyle = LineStyle(
        palette = palette,
        baseStyle = baseStyle,
        wrap = wrap,
        widthPx = (viewportWidthPx - startPadPx.toInt()).coerceAtLeast(0),
    )
    // A style / width change makes every cached layout stale.
    remember(palette, baseStyle, wrap, effStyle.widthPx) { state.cache.invalidate(); 0 }
    remember(wrap, lineHeightPx, lines) { state.syncGeometry(wrap, lineHeightPx, lines.lines.size); 0 }

    // Sticky-bottom: keep following the tail as content grows, if already at bottom.
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
                onViewportWidth(it.width)
                state.scroll.viewportPx = it.height
                // Re-clamp: the offset may have been set (sticky-bottom) before the
                // viewport was known, which would over-scroll past the true maximum.
                state.scroll.scrollTo(state.scroll.offsetPx)
            }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) state.scroll.scrollBy(dy * lineHeightPx * SCROLL_LINES_PER_NOTCH)
            }
            .pointerInput(lines, effStyle, startPadPx, topPadPx) {
                detectTapGestures(onTap = { pos ->
                    selection.setCaret(state.hitTest(pos.x, pos.y, lines, effStyle, topPadPx, startPadPx))
                })
            }
            .pointerInput(lines, effStyle, startPadPx, topPadPx) {
                detectDragGestures(
                    onDragStart = { pos ->
                        selection.beginAt(state.hitTest(pos.x, pos.y, lines, effStyle, topPadPx, startPadPx))
                    },
                    onDrag = { change, _ ->
                        val p = change.position
                        // Edge auto-scroll so a drag can extend past the viewport.
                        if (p.y < AUTO_SCROLL_EDGE_PX) state.scroll.scrollBy(-lineHeightPx.toFloat())
                        else if (p.y > size.height - AUTO_SCROLL_EDGE_PX) state.scroll.scrollBy(lineHeightPx.toFloat())
                        selection.extendTo(state.hitTest(p.x, p.y, lines, effStyle, topPadPx, startPadPx))
                    },
                )
            }
            .drawBehind {
                val offset = state.scroll.offsetPx
                val count = lines.lines.size
                if (count == 0) return@drawBehind

                val first = idx.lineAtOffset(offset.toInt())
                var i = first
                var geometryFirmedUp = false

                while (i < count) {
                    val top = idx.topOfLine(i).toFloat()
                    val yOnScreen = topPadPx + top - offset
                    if (yOnScreen > size.height) break

                    val line = lines.lines[i]
                    val layout = state.cache.layoutFor(line, effStyle)
                    val h = layout.size.height
                    if (wrap && h > 0) {
                        idx.setHeight(i, h)
                        geometryFirmedUp = true
                    }
                    if (layout.size.width > state.maxLineWidthPx) state.maxLineWidthPx = layout.size.width

                    if (showGutter && (line.severity == LogType.WARN || line.severity == LogType.ERROR)) {
                        drawRect(
                            color = if (line.severity == LogType.ERROR) errorColor else warnColor,
                            topLeft = Offset(0f, yOnScreen),
                            size = Size(gutterWidthPx, (if (wrap) h else lineHeightPx).toFloat()),
                        )
                    }

                    selection.rangeOnLine(i, line.text.length)?.let { r ->
                        val path = layout.getPathForRange(r.first, r.last + 1)
                        translate(left = startPadPx - state.hOffsetPx, top = yOnScreen) {
                            drawPath(path, selectionColor)
                        }
                    }

                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(startPadPx - state.hOffsetPx, yOnScreen),
                    )

                    i++
                }

                val total = idx.totalHeight
                if (geometryFirmedUp && state.scroll.contentHeightPx != total) {
                    state.scroll.contentHeightPx = total
                }
            },
    )
}

private const val SCROLL_LINES_PER_NOTCH = 3f
private const val AUTO_SCROLL_EDGE_PX = 24f
