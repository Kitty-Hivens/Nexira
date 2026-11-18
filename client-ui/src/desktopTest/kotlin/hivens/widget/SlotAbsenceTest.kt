// The test plays the host: it makes the app level the condition is read from.
@file:OptIn(HostApi::class)

package hivens.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.widget.api.HiddenWidgetDecorator
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalHiddenWidget
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SourceKey
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceInsets
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Bitmap
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A widget that has nothing to show where it stands leaves no trace in its slot, whatever the slot's
 * shape: no gap in a plain line or a wrapped one, no gap for a wrapped line of nothing, no room from
 * its own padding. A widget whose show condition does not hold is not even an item: no cell of a grid.
 *
 * Each case is drawn twice, once with the widget and once without it, and the two frames have to be
 * the same pixel for pixel. A marker drawn after the slot along each axis makes any room left behind
 * move something.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SlotAbsenceTest {

    // ── Plain and wrapped lines ───────────────────────────────────────

    @Test
    fun `a plain line with a widget that draws nothing is the line without it`() {
        for (flow in listOf(FlowSpec.Column, FlowSpec.Row)) {
            assertFrame(frame(slot(flow, "a", "empty", "b")), frame(slot(flow, "a", "b")), "$flow")
        }
    }

    @Test
    fun `a wrapped line gives a widget that draws nothing no gap`() {
        for (direction in listOf(FlowSpec.HORIZONTAL, FlowSpec.VERTICAL)) {
            val flow = FlowSpec(direction, wrap = 3)
            assertFrame(frame(slot(flow, "a", "empty", "b")), frame(slot(flow, "a", "b")), direction)
            assertFrame(frame(slot(flow, "empty", "a", "b")), frame(slot(flow, "a", "b")), "$direction, the first cell empty")
        }
    }

    @Test
    fun `a wrapped line of nothing takes no gap`() {
        for (direction in listOf(FlowSpec.HORIZONTAL, FlowSpec.VERTICAL)) {
            for (uniform in listOf(false, true)) {
                val flow = FlowSpec(direction, wrap = 2, uniform = uniform)
                assertFrame(frame(slot(flow, "a", "b", "empty", "empty")), frame(slot(flow, "a", "b")), "$direction, uniform $uniform")
            }
        }
    }

    @Test
    fun `the cases above would pass whatever the slot drew if a widget that draws took no room`() {
        val flow = FlowSpec(FlowSpec.HORIZONTAL, wrap = 3)
        assertFalse(frame(slot(flow, "a", "c", "b")).contentEquals(frame(slot(flow, "a", "b"))))
    }

    // ── Padding ───────────────────────────────────────────────────────

    @Test
    fun `the room around a widget that draws nothing collapses with it`() {
        val padded = Placement(padding = SurfaceInsets(all = 8f))
        for (flow in listOf(FlowSpec.Column, FlowSpec.Row, FlowSpec(FlowSpec.HORIZONTAL, wrap = 3))) {
            assertFrame(
                frame(slot(flow, "a", "empty", "b", placements = mapOf(1 to padded))),
                frame(slot(flow, "a", "b")),
                "$flow, nothing drawn",
            )
        }
        // Along an axis with no size there is nothing to keep room around: the flat one loses
        // its height and keeps the width it spans, as it would with no padding at all.
        assertFrame(
            frame(slot(FlowSpec.Column, "a", "flat", "b", placements = mapOf(1 to padded))),
            frame(slot(FlowSpec.Column, "a", "flat", "b")),
            "no height",
        )
    }

    @Test
    fun `the room around a widget that draws is kept`() {
        val padded = Placement(padding = SurfaceInsets(top = 8f))
        assertFalse(frame(slot(FlowSpec.Column, "a", "b", placements = mapOf(1 to padded))).contentEquals(frame(slot(FlowSpec.Column, "a", "b"))))
    }

    // ── A condition ───────────────────────────────────────────────────

    @Test
    fun `a widget whose condition does not hold is no item, not even a cell of a grid`() {
        val grid = FlowSpec.grid(2)
        val without = frame(slot(grid, "a", "b"))
        assertFrame(frame(slot(grid, "a", "shown", "b"), shown = false), without, "the condition answers false")
        assertFrame(frame(slot(grid, "a", "shown", "b"), shown = null), without, "nothing answers the condition")
        assertFrame(frame(slot(grid, "a", "shown", "b"), shown = true), frame(slot(grid, "a", "c", "b")), "the condition holds")
    }

    @Test
    fun `the condition is followed as its source changes`() {
        val flow = FlowSpec(FlowSpec.HORIZONTAL, wrap = 3)
        val without = frame(slot(flow, "a", "b"))
        val with = frame(slot(flow, "a", "c", "b"))
        val shown = MutableStateFlow(false)
        val live = Live(slot(flow, "a", "shown", "b"), shown)
        try {
            assertFrame(live.frame(), without, "hidden at first")
            shown.value = true
            assertFrame(live.frame(), with, "placed once it holds")
            shown.value = false
            assertFrame(live.frame(), without, "left out again")
        } finally {
            live.close()
        }
    }

    @Test
    fun `while somebody arranges, a hidden widget stands in its place through the decorator`() {
        val flow = FlowSpec(FlowSpec.HORIZONTAL, wrap = 3)
        val seen = ArrayList<String>()
        val decorator: HiddenWidgetDecorator = { descriptor, _, content ->
            seen += descriptor.kind.value
            content()
        }
        assertFrame(frame(slot(flow, "a", "shown", "b"), shown = false, hidden = decorator), frame(slot(flow, "a", "c", "b")), "drawn where it stands")
        assertEquals(listOf("shown"), seen.distinct(), "through the decorator")
        seen.clear()
        assertFrame(frame(slot(flow, "a", "shown", "b"), shown = true, hidden = decorator), frame(slot(flow, "a", "c", "b")), "drawn as it is")
        assertTrue(seen.isEmpty(), "a widget whose condition holds is drawn as it is: $seen")
    }

    // ── Harness ───────────────────────────────────────────────────────

    private fun assertFrame(actual: ByteArray, expected: ByteArray, message: String) =
        assertContentEquals(expected, actual, "the frames differ: $message")

    private fun slot(flow: FlowSpec, vararg kinds: String, placements: Map<Int, Placement> = emptyMap()) = SlotContent(
        widgets = kinds.mapIndexed { i, kind -> WidgetInstance(WidgetKind(kind), "w$i", placement = placements[i]) },
        flow = flow,
    )

    /** A block of [colour], [WIDGET] square. */
    private class Block(name: String, private val colour: Color, override val shownWhen: String? = null) : WidgetDescriptor {
        override val kind = WidgetKind(name)
        override val displayName = name
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = Box(Modifier.size(WIDGET.dp).background(colour))
    }

    /** A widget with nothing to show where it stands. */
    private class Empty(name: String, private val wide: Boolean) : WidgetDescriptor {
        override val kind = WidgetKind(name)
        override val displayName = name
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            if (wide) Box(Modifier.fillMaxWidth())
        }
    }

    private val registry = object : WidgetRegistry {
        private val kinds = listOf(
            Block("a", Color.Red),
            Block("b", Color.Green),
            Block("c", Color.Blue),
            // Drawn as c is, so a frame with it shown is the frame with c.
            Block("shown", Color.Blue, shownWhen = CONDITION.id),
            Empty("empty", wide = false),
            Empty("flat", wide = true),
        ).associateBy { it.kind }

        override fun all(): Map<WidgetKind, WidgetDescriptor> = kinds

        override fun get(kind: WidgetKind): WidgetDescriptor? = kinds[kind]
    }

    /** One scene over [content], with the condition answered by [shown], none where null, kept so the source can change under it. */
    private inner class Live(content: SlotContent, shown: MutableStateFlow<Boolean>?, hidden: HiddenWidgetDecorator? = null) : AutoCloseable {
        private val data = WidgetDataRegistry().apply { if (shown != null) register(CONDITION, flowSource(shown)) }
        private val app = WidgetScope.app(data, NoServices, Dispatchers.Unconfined, WidgetCommandRegistry())
        private val graph = LayoutGraph(surfaces = mapOf(SURFACE to SurfaceLayout(slots = mapOf(SLOT to content))))
        private val scene = ImageComposeScene(width = SIDE, height = SIDE, density = Density(1f)) {
            CompositionLocalProvider(
                LocalLayoutGraph provides graph,
                LocalWidgetRegistry provides registry,
                LocalWidgetDataRegistry provides data,
                LocalScope provides app,
                LocalHiddenWidget provides hidden,
            ) {
                Box(Modifier.fillMaxSize().background(PAGE)) {
                    Column {
                        Row {
                            SlotRenderer(SURFACE, SLOT, spacing = GAP.dp)
                            Box(Modifier.size(MARKER.dp).background(Color.Black))
                        }
                        Box(Modifier.size(MARKER.dp).background(Color.Black))
                    }
                }
            }
        }
        private var time = 0L

        fun frame(): ByteArray {
            repeat(3) {
                scene.render(time).close()
                time += 16_000_000L
            }
            val image = scene.render(time)
            time += 16_000_000L
            val bitmap = Bitmap.makeFromImage(image)
            image.close()
            return try {
                assertNotNull(bitmap.readPixels(bitmap.imageInfo, bitmap.imageInfo.minRowBytes, 0, 0))
            } finally {
                bitmap.close()
            }
        }

        override fun close() = scene.close()
    }

    private fun frame(content: SlotContent, shown: Boolean? = null, hidden: HiddenWidgetDecorator? = null): ByteArray =
        Live(content, shown?.let { MutableStateFlow(it) }, hidden).use { it.frame() }

    /** A level with no services: the condition is read from keys alone. */
    private object NoServices : LevelServicesHost {
        override val services: LevelServices = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null

            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException("no services here")
        }

        override fun child(level: ScopeLevel): LevelServicesHost = this

        override fun close() = Unit
    }

    private companion object {
        const val SIDE = 300
        const val WIDGET = 30
        const val GAP = 10
        const val MARKER = 6
        val PAGE = Color(0xFFFF00FF)
        val SURFACE = SurfaceId("s")
        val SLOT = SlotId("main")
        val CONDITION = SourceKey<Boolean>("test.shown", ScopeLevel.App)
    }
}
