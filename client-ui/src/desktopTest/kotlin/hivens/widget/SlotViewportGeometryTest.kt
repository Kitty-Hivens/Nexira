package hivens.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
    fun `a page is as long as a far widget from its first frame`() {
        // Held to a length measured last frame, the page crept out to a far widget a
        // widget's height at a time, and this one would have taken two hundred frames.
        val page = Probe(
            SlotContent(
                widgets = listOf(widget("a", "fill.a", Placement(x = 0f, y = 5000f, width = 50f, height = 50f))),
                flow = null,
                viewport = ViewportSpec.ScrollDown,
            ),
        )
        page.wheel(down = 5000f)
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
    private inner class Probe(content: SlotContent) {
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

        fun wheel(down: Float = 0f, right: Float = 0f) {
            val at = Offset(SIDE / 2f, SIDE / 2f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(right, down))
            frame = pump(90)
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
