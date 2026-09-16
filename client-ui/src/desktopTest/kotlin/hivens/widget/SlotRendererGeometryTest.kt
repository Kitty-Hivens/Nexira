package hivens.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the renderer actually puts things, read off the pixels.
 *
 * The two slot modes replaced five branches, and the arithmetic in them is new:
 * an anchor decides which corner an offset runs from, and a lattice turns a cell
 * address into dp against a width it only learns by being measured. None of that
 * is visible to a test that asserts the model, and all of it is the kind of thing
 * that compiles while drawing in the wrong place.
 *
 * So each widget paints one flat colour and the assertions are coordinates. A
 * bright page reads through anywhere nothing was drawn.
 */
class SlotRendererGeometryTest {

    private val surface = SurfaceId("s")
    private val slot = SlotId("main")

    // ── Placement, measured in dp ─────────────────────────────────────

    @Test
    fun `an offset from the top start corner runs down and to the right`() {
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(anchor = Placement.TOP_START, x = 10f, y = 10f, width = 40f, height = 40f))),
                flow = null,
            ),
        )
        assertEquals(A, frame.at(30, 30), "the widget is not where the offset put it")
        assertEquals(PAGE, frame.at(5, 5), "nothing should be drawn before the offset")
        assertEquals(PAGE, frame.at(60, 60), "nothing should be drawn past the size")
    }

    @Test
    fun `an offset from the bottom end corner runs inward from it`() {
        // This is the whole reason the anchor exists. The same record positioned
        // from the top left is clipped on a narrower window and leaves dead margin
        // on a wider one, which is what shipped.
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(anchor = Placement.BOTTOM_END, x = 10f, y = 10f, width = 40f, height = 40f))),
                flow = null,
            ),
        )
        assertEquals(A, frame.at(SIDE - 30, SIDE - 30), "the widget did not park in the far corner")
        assertEquals(PAGE, frame.at(SIDE - 5, SIDE - 5), "the inset ran outward instead of inward")
        assertEquals(PAGE, frame.at(30, 30), "the widget stayed at the origin")
    }

    @Test
    fun `a centred widget lands in the middle`() {
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(anchor = Placement.CENTER, width = 40f, height = 40f))),
                flow = null,
            ),
        )
        assertEquals(A, frame.at(SIDE / 2, SIDE / 2))
        assertEquals(PAGE, frame.at(10, 10))
    }

    @Test
    fun `the layer decides which of two overlapping widgets is in front`() {
        val under = box("a", Placement(x = 20f, y = 20f, width = 60f, height = 60f, z = 0))
        val over = box("b", Placement(x = 40f, y = 40f, width = 60f, height = 60f, z = 5), kind = "b")
        assertEquals(B, render(SlotContent(widgets = listOf(under, over), flow = null)).at(60, 60))
        // The order in the list is not the order on screen: reversing the list must
        // not change who is in front, because the layer is what decides.
        assertEquals(B, render(SlotContent(widgets = listOf(over, under), flow = null)).at(60, 60))
    }

    // ── Placement, measured in cells ──────────────────────────────────

    @Test
    fun `a cell address becomes dp against the measured width`() {
        // Four columns across 200dp with no gutter is a 50dp cell, so the widget at
        // column one starts at 50 and ends at 100.
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(x = 1f, y = 0f, width = 1f, height = 1f))),
                flow = null,
                grid = 4,
            ),
        )
        assertEquals(PAGE, frame.at(25, 25), "column zero should be empty")
        assertEquals(A, frame.at(75, 25), "column one did not land on its cell")
        assertEquals(PAGE, frame.at(125, 25), "the span covered more than one cell")
    }

    @Test
    fun `a span of two cells covers two cells`() {
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(x = 0f, y = 0f, width = 2f, height = 1f))),
                flow = null,
                grid = 4,
            ),
        )
        assertEquals(A, frame.at(25, 25))
        assertEquals(A, frame.at(75, 25))
        assertEquals(PAGE, frame.at(125, 25))
    }

    @Test
    fun `a widget parked past a narrowed lattice is drawn inside it`() {
        // The count is a number in a menu and the stored position is deliberately
        // not rescaled when it moves, so lowering it used to leave a widget at a
        // column that no longer exists: drawn past the slot, off the window and
        // out of reach, with no way back but raising the count again.
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(x = 5f, y = 0f, width = 1f, height = 1f))),
                flow = null,
                grid = 2,
            ),
        )
        assertEquals(A, frame.at(150, 25), "the widget was not clamped into the last column")
        assertEquals(PAGE, frame.at(50, 25), "and it did not fall back to the first")
    }

    @Test
    fun `a span wider than the lattice is clamped to it`() {
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(x = 0f, y = 0f, width = 9f, height = 1f))),
                flow = null,
                grid = 2,
            ),
        )
        assertEquals(A, frame.at(50, 25))
        assertEquals(A, frame.at(150, 25))
        assertEquals(PAGE, frame.at(50, 150), "the row span was not clamped with it")
    }

    @Test
    fun `a widget that names only one axis is sized on that axis alone`() {
        // Requiring both silently threw the one away, while the record can express
        // it and the editor can write it.
        val frame = render(
            SlotContent(
                widgets = listOf(box("a", Placement(width = 60f, height = 0f))),
                flow = null,
            ),
        )
        assertEquals(A, frame.at(30, 190), "the width was not applied, or the height was capped with it")
        assertEquals(PAGE, frame.at(90, 190), "the width was ignored")
    }

    // ── Flow ──────────────────────────────────────────────────────────

    @Test
    fun `a horizontal flow lays its children across`() {
        val frame = render(
            SlotContent(
                widgets = listOf(
                    box("a", Placement(weight = 1f)),
                    box("b", Placement(weight = 1f), kind = "b"),
                ),
                flow = FlowSpec.Row,
            ),
        )
        assertEquals(A, frame.at(50, 100))
        assertEquals(B, frame.at(150, 100))
    }

    @Test
    fun `a vertical flow lays its children down`() {
        val frame = render(
            SlotContent(
                widgets = listOf(
                    box("a", Placement(weight = 1f)),
                    box("b", Placement(weight = 1f), kind = "b"),
                ),
                flow = FlowSpec.Column,
            ),
        )
        assertEquals(A, frame.at(100, 50))
        assertEquals(B, frame.at(100, 150))
    }

    @Test
    fun `a wrapped uniform flow gives equal widths and wraps at the line length`() {
        // Three children, two per line. Equal is about the width: a flow sizes the
        // cross axis by what the child asks for, so these ask for a fixed height
        // and the lines stack instead of the first one eating the slot.
        val frame = render(
            SlotContent(
                widgets = listOf(
                    box("a", null),
                    box("b", null, kind = "b"),
                    box("c", null, kind = "c"),
                ),
                flow = FlowSpec.grid(2),
            ),
            fixedHeight = 100,
        )
        assertEquals(A, frame.at(50, 25), "first cell of the first line")
        assertEquals(B, frame.at(150, 25), "second cell of the first line")
        assertEquals(C, frame.at(50, 125), "the third child wrapped to the next line")
        assertEquals(PAGE, frame.at(150, 125), "the short line is padded, not stretched")
    }

    // ── Harness ───────────────────────────────────────────────────────

    private fun box(id: String, placement: Placement?, kind: String = "a") =
        WidgetInstance(WidgetKind(kind), id, placement = placement)

    /**
     * A flat colour filling whatever box the renderer hands it, or a fixed height
     * when the slot is a flow and the cross axis is the child's to name.
     */
    private class ColourWidget(
        override val kind: WidgetKind,
        private val colour: Color,
        private val heightDp: Int,
    ) : WidgetDescriptor {
        override val displayName: String get() = kind.value
        override val removable: Boolean get() = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            val sized = if (heightDp > 0) Modifier.fillMaxWidth().height(heightDp.dp) else Modifier.fillMaxSize()
            Box(sized.background(colour))
        }
    }

    private class Registry(private val kinds: Map<WidgetKind, WidgetDescriptor>) : WidgetRegistry {
        override fun all() = kinds
        override fun get(kind: WidgetKind) = kinds[kind]
    }

    private class Frame(private val bitmap: Bitmap) {
        fun at(x: Int, y: Int): Triple<Int, Int, Int> {
            val c = bitmap.getColor(x, y)
            return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(content: SlotContent, fixedHeight: Int = 0): Frame {
        val graph = LayoutGraph(surfaces = mapOf(surface to SurfaceLayout(slots = mapOf(slot to content))))
        val registry = Registry(
            mapOf(
                WidgetKind("a") to ColourWidget(WidgetKind("a"), RED, fixedHeight),
                WidgetKind("b") to ColourWidget(WidgetKind("b"), GREEN, fixedHeight),
                WidgetKind("c") to ColourWidget(WidgetKind("c"), BLUE, fixedHeight),
            ),
        )
        val scene = ImageComposeScene(width = SIDE, height = SIDE, density = Density(1f)) {
            CompositionLocalProvider(
                LocalLayoutGraph provides graph,
                LocalWidgetRegistry provides registry,
            ) {
                Box(Modifier.fillMaxSize().background(PAGE_COLOUR)) {
                    SlotRenderer(surface, slot, Modifier.fillMaxSize())
                }
            }
        }
        val image = try {
            scene.render()
        } finally {
            scene.close()
        }
        File(OUT).mkdirs()
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(OUT, "slot-${content.hashCode()}.png").writeBytes(it) }
        return Frame(Bitmap.makeFromImage(image))
    }

    private companion object {
        const val SIDE = 200
        const val OUT = "build/render-probe"

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
