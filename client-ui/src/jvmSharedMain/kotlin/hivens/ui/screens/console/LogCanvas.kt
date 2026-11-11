package hivens.ui.screens.console

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import hivens.ui.screens.ConsolePalette
import hivens.ui.utils.LogType
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

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

    private var lastCount: Int = -1

    fun sampleLineHeightPx(base: TextStyle): Int =
        measurer.measure(text = "Ag", style = base).size.height.coerceAtLeast(1)

    /** Fresh index (every line at the estimate) and reset the accumulated max line
     *  width. Called when shape / width / rendering changes -- all measured heights
     *  are stale then anyway. */
    fun rebuildIndex(wrap: Boolean, lineHeightPx: Int, count: Int) {
        heightIndex = if (wrap) FenwickHeightIndex(count, lineHeightPx)
                      else ConstantHeightIndex(lineHeightPx).also { it.reset(count, lineHeightPx) }
        lastCount = count
        maxLineWidthPx = 0
    }

    /** React to a line-count change WITHOUT dropping measured heights on a pure
     *  append: Fenwick grows (keeps every measured height), constant re-seeds in
     *  O(1); a shrink renumbers indices, so rebuild. */
    fun syncCount(wrap: Boolean, lineHeightPx: Int, count: Int) {
        if (count == lastCount) return
        val hi = heightIndex
        when {
            hi is FenwickHeightIndex && count > lastCount -> hi.grow(count, lineHeightPx)
            hi is FenwickHeightIndex                      -> rebuildIndex(wrap, lineHeightPx, count)
            else                                          -> hi.reset(count, lineHeightPx)
        }
        lastCount = count
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
    fun horizontalScrollbarAdapter(viewportWidthPx: () -> Int): ScrollMetrics = object : ScrollMetrics {
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
    // Value-equal key over every render-affecting input EXCEPT the entries (timestamps,
    // query, regex, filters, rules). A change drops the layout cache and rebuilds the
    // height index; a pure entries append leaves it untouched (append stays O(1)).
    contentKey: Any?,
    onInteract: () -> Unit = {},
    /** A finger picked a line, so whoever holds this canvas offers what a right click would. */
    onTouchSelection: () -> Unit = {},
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
    // Rendering or geometry inputs changed (theme / font / wrap / width / timestamps /
    // query / rules) -> every cached layout and measured height is stale: drop the
    // cache and rebuild the index to estimates.
    remember(palette, baseStyle, wrap, effStyle.widthPx, contentKey) {
        state.cache.invalidate()
        state.rebuildIndex(wrap, lineHeightPx, lines.lines.size)
        0
    }
    // Line count moved (append / clear): grow or re-seed the index without dropping
    // measured heights on a pure append, refresh the content height, and -- if we are
    // following the tail -- pin to the (estimate) bottom; the draw pass firms it up.
    remember(lines) {
        state.syncCount(wrap, lineHeightPx, lines.lines.size)
        state.scroll.contentHeightPx = state.heightIndex.totalHeight
        if (state.scroll.follow) state.scroll.scrollToBottom()
        0
    }

    // The gesture handlers read these at the moment they hit-test rather than
    // being keyed on them. `lines` is a new value on every console snapshot, so a
    // handler keyed on it was torn down with each appended line, and a selection
    // stopped following the pointer as soon as a running game printed anything.
    val currentLines by rememberUpdatedState(lines)
    val currentStyle by rememberUpdatedState(effStyle)
    val wrapNow = rememberUpdatedState(wrap)
    val currentStartPad by rememberUpdatedState(startPadPx)
    val currentTopPad by rememberUpdatedState(topPadPx)
    fun hitAt(p: Offset): DocPos = state.hitTest(p.x, p.y, currentLines, currentStyle, currentTopPad, currentStartPad)

    // Continuous edge auto-scroll: while a drag-select parks the pointer at the top
    // or bottom edge, keep scrolling and extending the selection to the pointer even
    // though no new pointer events fire.
    var autoScroll by remember { mutableStateOf(0) } // -1 up, 0 off, +1 down
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(autoScroll) {
        if (autoScroll == 0) return@LaunchedEffect
        while (true) {
            state.scroll.scrollBy(autoScroll * lineHeightPx * AUTO_SCROLL_STEP_FACTOR)
            selection.extendTo(hitAt(dragPos))
            delay(AUTO_SCROLL_TICK_MS.milliseconds)
        }
    }

    val idx = state.heightIndex
    val haptics = LocalHapticFeedback.current
    val flingScope = rememberCoroutineScope()
    val fling = remember { arrayOfNulls<Job>(1) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                viewportWidthPx = it.width
                onViewportWidth(it.width)
                state.scroll.viewportPx = it.height
                // Re-clamp to the new range, preserving follow: if following, stay
                // pinned to the (new) bottom; the offset may have been set before the
                // viewport was known.
                state.scroll.reclamp()
            }
            .onWheelScroll { dy ->
                if (dy != 0f) state.scroll.scrollBy(dy * lineHeightPx * SCROLL_LINES_PER_NOTCH)
            }
            .pointerInput(Unit) {
                // A finger has one way to move and two things to do with it: a drag scrolls, and only a hold first
                // starts a selection, which a mouse starts by dragging. Read ahead of the mouse's gestures below,
                // which give up on what this consumes, and a plain tap is left to them.
                val decay = splineBasedDecay<Float>(this)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Touch) return@awaitEachGesture
                    fling[0]?.cancel()
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    val holdEnds = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    var mode = TouchMode.Undecided
                    var at = down.position
                    var now = down.uptimeMillis
                    while (true) {
                        val event = if (mode == TouchMode.Undecided) {
                            withTimeoutOrNull((holdEnds - now).coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                        if (event == null) {
                            val line = hitAt(at).line
                            val text = currentLines.lines.getOrNull(line)?.text
                            if (text == null) {
                                mode = TouchMode.Scrolling
                                continue
                            }
                            mode = TouchMode.Selecting
                            onInteract()
                            selection.select(DocPos(line, 0), DocPos(line, text.length))
                            onTouchSelection()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragPos = at
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        now = change.uptimeMillis
                        if (!change.pressed) {
                            if (mode != TouchMode.Undecided) change.consume()
                            break
                        }
                        val p = change.position
                        tracker.addPointerInputChange(change)
                        if (mode == TouchMode.Undecided && (p - down.position).getDistance() > viewConfiguration.touchSlop) {
                            mode = TouchMode.Scrolling
                            at = p
                        }
                        when (mode) {
                            TouchMode.Undecided -> at = p
                            TouchMode.Scrolling -> {
                                val _ = state.scroll.scrollBy(at.y - p.y)
                                if (!wrapNow.value) {
                                    state.hOffsetPx = (state.hOffsetPx + at.x - p.x).coerceIn(0f, state.maxHOffset(viewportWidthPx))
                                }
                                at = p
                                change.consume()
                            }
                            TouchMode.Selecting -> {
                                dragPos = p
                                autoScroll = when {
                                    p.y < AUTO_SCROLL_EDGE_PX               -> -1
                                    p.y > size.height - AUTO_SCROLL_EDGE_PX -> 1
                                    else                                    -> 0
                                }
                                selection.extendTo(hitAt(p))
                                change.consume()
                            }
                        }
                    }
                    autoScroll = 0
                    if (mode == TouchMode.Scrolling) {
                        val velocity = -tracker.calculateVelocity().y
                        if (abs(velocity) > 0f) {
                            fling[0] = flingScope.launch {
                                var last = 0f
                                AnimationState(0f, velocity).animateDecay(decay) {
                                    val moved = state.scroll.scrollBy(value - last)
                                    last = value
                                    if (moved == 0f) cancelAnimation()
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos ->
                    onInteract()
                    selection.setCaret(hitAt(pos))
                })
            }
            .pointerInput(Unit) {
                // A gesture coroutine that is cancelled runs neither end handler, so
                // the edge scroll is disarmed here too. Left armed, it scrolled and
                // extended the selection every frame with no button held.
                try {
                    detectDragGestures(
                        onDragStart = { pos ->
                            onInteract()
                            dragPos = pos
                            selection.beginAt(hitAt(pos))
                        },
                        onDrag = { change, _ ->
                            val p = change.position
                            dragPos = p
                            // Arm / disarm continuous auto-scroll from the pointer's edge
                            // proximity; the LaunchedEffect drives it while held.
                            autoScroll = when {
                                p.y < AUTO_SCROLL_EDGE_PX               -> -1
                                p.y > size.height - AUTO_SCROLL_EDGE_PX -> 1
                                else                                    -> 0
                            }
                            selection.extendTo(hitAt(p))
                        },
                        onDragEnd = { autoScroll = 0 },
                        onDragCancel = { autoScroll = 0 },
                    )
                } finally {
                    autoScroll = 0
                }
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
                    // The followed tail just firmed up to its real height -> re-pin to
                    // the true bottom so the newest lines stay in view.
                    state.scroll.stickIfFollowing()
                }
            },
    )
}

/** What a finger on the log turned out to be doing. */
private enum class TouchMode { Undecided, Scrolling, Selecting }

private const val SCROLL_LINES_PER_NOTCH = 3f
private const val AUTO_SCROLL_EDGE_PX = 24f
private const val AUTO_SCROLL_STEP_FACTOR = 0.6f
private const val AUTO_SCROLL_TICK_MS = 16L
