package hivens.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.ViewportSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a scrolling slot puts things, read off the pixels, before and after the
 * wheel.
 *
 * The rules are the reason the viewport is the kernel's and not a surface's: a
 * weight along the scrolling axis is a share of one screen, a widget that fills
 * gets one screen, a lazy list is measured instead of thrown, and a placed widget
 * further down than the window is a page the wheel reaches. Each of those
 * compiles while drawing nothing, so each one is a coordinate here.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SlotViewportGeometryTest {

    private val surface = SurfaceId("s")
    private val slot = SlotId("main")

    // ── Down ──────────────────────────────────────────────────────────

    @Test
    fun `a column longer than its slot scrolls to its end`() {
        val content = SlotContent(
            widgets = listOf(widget("a", "tall.a"), widget("b", "tall.b"), widget("c", "tall.c")),
            flow = FlowSpec.Column,
        )
        val static = Probe(content)
        assertEquals(B, static.at(100, 150), "a static column shows the second widget here")

        val page = Probe(content.copy(viewport = ViewportSpec.ScrollDown))
        assertEquals(A, page.at(100, 50))
        assertEquals(B, page.at(100, 150))
        page.wheel(down = 50f)
        assertEquals(C, page.at(100, 150), "the third widget was not reached")
        assertEquals(B, page.at(100, 50))
    }

    @Test
    fun `a weight along the scroll is a share of one screen`() {
        // Static, two weights split what the natural widget leaves: 50 each. On a page
        // they split one screen and the natural widget adds to the page instead.
        val content = SlotContent(
            widgets = listOf(
                widget("a", "fill.a", Placement(weight = 1f)),
                widget("b", "fill.b", Placement(weight = 1f)),
                widget("c", "tall.c"),
            ),
            flow = FlowSpec.Column,
            viewport = ViewportSpec.ScrollDown,
        )
        val page = Probe(content)
        assertEquals(A, page.at(100, 90))
        assertEquals(B, page.at(100, 110), "two weights of one should be half a screen each")
        assertEquals(B, page.at(100, 190), "the natural widget squeezed the weights")
        page.wheel(down = 50f)
        assertEquals(C, page.at(100, 150))
    }

    @Test
    fun `a widget that fills its slot fills one screen of a page`() {
        // Undeclared and filling, so on an unbounded axis it would be nothing at all.
        val page = Probe(
            SlotContent(
                widgets = listOf(widget("a", "fill.a"), widget("b", "fill.b")),
                flow = FlowSpec.Column,
                viewport = ViewportSpec.ScrollDown,
            ),
        )
        assertEquals(A, page.at(100, 10))
        assertEquals(A, page.at(100, 190), "the first one is a full screen")
        page.wheel(down = 50f)
        assertEquals(B, page.at(100, 10), "and the second one is the next")
        assertEquals(B, page.at(100, 190))
    }

    @Test
    fun `a lazy list on a page is measured rather than thrown`() {
        val page = Probe(
            SlotContent(
                widgets = listOf(widget("l", "lazy")),
                flow = FlowSpec.Column,
                viewport = ViewportSpec.ScrollDown,
            ),
        )
        assertEquals(C, page.at(100, 190), "the list was given one screen to list in")
    }

    @Test
    fun `a placed widget below the window is reached by scrolling`() {
        val page = Probe(
            SlotContent(
                widgets = listOf(widget("a", "fill.a", Placement(x = 0f, y = 300f, width = 50f, height = 50f))),
                flow = null,
                viewport = ViewportSpec.ScrollDown,
            ),
        )
        assertEquals(PAGE, page.at(25, 190), "nothing is placed on the first screen")
        page.wheel(down = 50f)
        // The page is as long as the widget's reach, 350, so it stops 150 down.
        assertEquals(A, page.at(25, 175), "the page did not grow to the widget")
        assertEquals(PAGE, page.at(25, 140))
    }

    @Test
    fun `a widget sent far down a page takes the page with it at once`() {
        // The page has already been measured one screen long. An undo or a preset
        // then puts the widget five thousand down. Held to the length measured the
        // frame before, the page crept out toward it a widget's height per frame and
        // would have taken two hundred frames to get there.
        val near = SlotContent(
            widgets = listOf(widget("a", "fill.a", Placement(x = 0f, y = 20f, width = 50f, height = 50f))),
            flow = null,
            viewport = ViewportSpec.ScrollDown,
        )
        val page = Probe(near)
        page.show(near.copy(widgets = listOf(widget("a", "fill.a", Placement(x = 0f, y = 5000f, width = 50f, height = 50f)))), frames = 2)
        page.wheel(down = 5000f, frames = 20)
        assertEquals(A, page.at(25, 175), "the page stopped short of the widget")
    }

    @Test
    fun `a placed widget inside the first screen leaves the page one screen long`() {
        val page = Probe(
            SlotContent(
                widgets = listOf(widget("a", "fill.a", Placement(x = 0f, y = 20f, width = 50f, height = 50f))),
                flow = null,
                viewport = ViewportSpec.ScrollDown,
            ),
        )
        page.wheel(down = 50f)
        assertEquals(A, page.at(25, 45), "a page that fits must not move")
    }

    @Test
    fun `a widget attached to the bottom sits at the end of the page`() {
        val page = Probe(
            SlotContent(
                widgets = listOf(
                    widget("a", "fill.a", Placement(x = 0f, y = 300f, width = 50f, height = 50f)),
                    widget("b", "fill.b", Placement(anchor = Placement.BOTTOM_END, width = 50f, height = 50f)),
                ),
                flow = null,
                viewport = ViewportSpec.ScrollDown,
            ),
        )
        assertEquals(PAGE, page.at(175, 175), "the bottom of the page is not the bottom of the window")
        page.wheel(down = 50f)
        assertEquals(B, page.at(175, 175))
    }

    // ── Sideways ──────────────────────────────────────────────────────

    @Test
    fun `a row scrolls sideways to its end`() {
        val page = Probe(
            SlotContent(
                widgets = listOf(widget("a", "fill.a"), widget("b", "fill.b")),
                flow = FlowSpec.Row,
                viewport = ViewportSpec.ScrollRight,
            ),
        )
        assertEquals(A, page.at(190, 100), "the first widget is a full screen wide")
        page.wheel(right = 50f)
        assertEquals(B, page.at(10, 100))
        assertEquals(B, page.at(190, 100))
    }

    @Test
    fun `a lattice that scrolls sideways counts rows and grows to the right`() {
        // Two rows across 200: a 100 cell, and the columns run on past the window.
        val page = Probe(
            SlotContent(
                widgets = listOf(
                    widget("a", "fill.a", Placement(x = 0f, y = 1f, width = 1f, height = 1f)),
                    widget("b", "fill.b", Placement(x = 3f, y = 0f, width = 1f, height = 1f)),
                ),
                flow = null,
                grid = 2,
                viewport = ViewportSpec.ScrollRight,
            ),
        )
        assertEquals(A, page.at(50, 150), "row one did not land on its cell")
        assertEquals(PAGE, page.at(50, 50))
        page.wheel(right = 50f)
        assertEquals(B, page.at(150, 50), "column three is past the window and the page reaches it")
    }

    // ── Map ───────────────────────────────────────────────────────────

    private fun map(vararg widgets: WidgetInstance) =
        SlotContent(widgets = widgets.toList(), flow = null, viewport = ViewportSpec.Map)

    @Test
    fun `a widget on a map sits at its own point on the plane`() {
        val page = Probe(map(widget("a", "fill.a", Placement(x = 50f, y = 50f, width = 50f, height = 50f))))
        assertEquals(A, page.at(75, 75))
        assertEquals(PAGE, page.at(25, 25))
        assertEquals(PAGE, page.at(125, 125))
    }

    @Test
    fun `the wheel moves a map down and Shift with it sideways`() {
        val page = Probe(map(widget("a", "fill.a", Placement(x = 250f, y = 250f, width = 50f, height = 50f))))
        assertEquals(PAGE, page.at(190, 190), "the widget starts past the corner of the view")
        // One notch is 64: the plane moves up and left by that much.
        page.wheel(down = 1f, frames = 4)
        page.wheel(right = 1f, frames = 4)
        assertEquals(A, page.at(190, 190), "the plane did not move under the wheel")
        assertEquals(PAGE, page.at(180, 180), "it moved further than one notch")
    }

    @Test
    fun `dragging empty space moves the map, to the left of the origin too`() {
        val page = Probe(map(widget("a", "fill.a", Placement(x = -100f, y = 20f, width = 50f, height = 50f))))
        assertEquals(PAGE, page.at(5, 45), "a widget left of the origin starts out of view")
        page.drag(from = Offset(150f, 150f), to = Offset(300f, 150f))
        assertEquals(A, page.at(75, 45), "the plane followed the pointer by 150")
    }

    @Test
    fun `a corner named on a map counts from the origin`() {
        // The record keeps its corner for whenever the slot stops being a map.
        val page = Probe(map(widget("a", "fill.a", Placement(anchor = Placement.BOTTOM_END, x = 10f, y = 10f, width = 40f, height = 40f))))
        assertEquals(A, page.at(30, 30))
        assertEquals(PAGE, page.at(180, 180))
    }

    @Test
    fun `a flow on a map is shown as it would be static`() {
        val page = Probe(
            SlotContent(widgets = listOf(widget("a", "tall.a"), widget("b", "tall.b")), flow = FlowSpec.Column, viewport = ViewportSpec.Map),
        )
        assertEquals(A, page.at(100, 50))
        assertEquals(B, page.at(100, 150))
    }

    // ── Harness ───────────────────────────────────────────────────────

    private fun widget(id: String, kind: String, placement: Placement? = null) =
        WidgetInstance(WidgetKind(kind), id, placement = placement)

    /** A flat colour, either a fixed 100 tall or filling whatever it is handed. */
    private class ColourWidget(
        override val kind: WidgetKind,
        private val colour: Color,
        private val tall: Boolean,
    ) : WidgetDescriptor {
        override val displayName: String get() = kind.value
        override val removable: Boolean get() = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            val sized = if (tall) Modifier.fillMaxWidth().height(100.dp) else Modifier.fillMaxSize()
            Box(sized.background(colour))
        }
    }

    /** A lazy list that fills, the shape Compose refuses on an unbounded axis. */
    private object LazyWidget : WidgetDescriptor {
        override val kind = WidgetKind("lazy")
        override val displayName: String get() = kind.value
        override val removable: Boolean get() = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            LazyColumn(Modifier.fillMaxSize().background(BLUE)) {
                items(50) { Box(Modifier.fillMaxWidth().height(20.dp)) }
            }
        }
    }

    private class Registry(private val kinds: Map<WidgetKind, WidgetDescriptor>) : WidgetRegistry {
        override fun all() = kinds
        override fun get(kind: WidgetKind) = kinds[kind]
    }

    private val registry = Registry(
        listOf(
            ColourWidget(WidgetKind("tall.a"), RED, tall = true),
            ColourWidget(WidgetKind("tall.b"), GREEN, tall = true),
            ColourWidget(WidgetKind("tall.c"), BLUE, tall = true),
            ColourWidget(WidgetKind("fill.a"), RED, tall = false),
            ColourWidget(WidgetKind("fill.b"), GREEN, tall = false),
            LazyWidget,
        ).associateBy { it.kind },
    )

    /**
     * One slot in a 200 by 200 window, kept open so a wheel can move it. Frames are
     * pumped on a clock, because a wheel on the desktop scrolls with an animation
     * and a single render would read the first frame of it.
     */
    private inner class Probe(initial: SlotContent) {
        private var content by mutableStateOf(initial)
        private val scene = ImageComposeScene(width = SIDE, height = SIDE, density = Density(1f)) {
            val graph = LayoutGraph(surfaces = mapOf(surface to SurfaceLayout(slots = mapOf(slot to content))))
            CompositionLocalProvider(LocalLayoutGraph provides graph, LocalWidgetRegistry provides registry) {
                Box(Modifier.fillMaxSize().background(PAGE_COLOUR)) {
                    SlotRenderer(surface, slot, Modifier.fillMaxSize())
                }
            }
        }
        private var nanos = 0L
        private var frame: Bitmap = pump(4)

        private fun pump(frames: Int): Bitmap {
            repeat(frames - 1) { scene.render(nanos).close(); nanos += FRAME_NANOS }
            val image = scene.render(nanos)
            nanos += FRAME_NANOS
            return Bitmap.makeFromImage(image)
        }

        fun wheel(down: Float = 0f, right: Float = 0f, frames: Int = 90) {
            val at = Offset(SIDE / 2f, SIDE / 2f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(right, down))
            frame = pump(frames)
        }

        /** A primary-button drag from [from] to [to], in steps, then a few frames to settle. */
        fun drag(from: Offset, to: Offset) {
            val down = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, from)
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = down, button = PointerButton.Primary)
            for (i in 1..10) {
                val at = from + (to - from) * (i / 10f)
                scene.render(nanos).close(); nanos += FRAME_NANOS
                scene.sendPointerEvent(PointerEventType.Move, at, buttons = down)
            }
            scene.sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Primary)
            frame = pump(4)
        }

        /** Swaps the slot's record, the way an edit does, and draws [frames] frames of it. */
        fun show(next: SlotContent, frames: Int) {
            content = next
            frame = pump(frames)
        }

        fun at(x: Int, y: Int): Triple<Int, Int, Int> {
            val c = frame.getColor(x, y)
            return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
    }

    private companion object {
        const val SIDE = 200
        const val FRAME_NANOS = 16_000_000L

        val PAGE_COLOUR = Color(0xFFFF00FF)
        val RED = Color(0xFFFF0000)
        val GREEN = Color(0xFF00FF00)
        val BLUE = Color(0xFF0000FF)

        val PAGE = Triple(255, 0, 255)
        val A = Triple(255, 0, 0)
        val B = Triple(0, 255, 0)
        val C = Triple(0, 0, 255)
    }
}
