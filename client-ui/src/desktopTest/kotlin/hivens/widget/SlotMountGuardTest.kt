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
import hivens.widget.api.LocalRefusedMount
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A widget does not contain a surface, it opens one, so a widget that opens the
 * surface it sits on is a loop nothing in the model can see. The kernel refuses
 * the second opening instead of recursing until the stack runs out, which the
 * crash recovery would then read as an ordinary crash and retry.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SlotMountGuardTest {

    private val surface = SurfaceId("s")
    private val other = SurfaceId("t")
    private val slot = SlotId("main")

    /** Opens [target]'s main slot inside itself, 100 tall. */
    private class Opener(override val kind: WidgetKind, private val target: SurfaceId) : WidgetDescriptor {
        override val displayName: String get() = kind.value
        override val removable: Boolean get() = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            SlotRenderer(target, SlotId("main"), Modifier.fillMaxWidth().height(100.dp))
        }
    }

    private class Red : WidgetDescriptor {
        override val kind = WidgetKind("red")
        override val displayName: String get() = kind.value
        override val removable: Boolean get() = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            Box(Modifier.fillMaxWidth().height(50.dp).background(Color.Red))
        }
    }

    private fun render(graph: LayoutGraph, refused: MutableList<SurfaceId>): Bitmap {
        val registry = object : WidgetRegistry {
            val kinds = listOf(Opener(WidgetKind("self"), surface), Opener(WidgetKind("other"), other), Red()).associateBy { it.kind }
            override fun all() = kinds
            override fun get(kind: WidgetKind) = kinds[kind]
        }
        val scene = ImageComposeScene(200, 400, density = Density(1f)) {
            CompositionLocalProvider(
                LocalLayoutGraph provides graph,
                LocalWidgetRegistry provides registry,
                LocalRefusedMount provides { refused += it },
            ) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    SlotRenderer(surface, slot, Modifier.fillMaxSize())
                }
            }
        }
        return try {
            Bitmap.makeFromImage(scene.render())
        } finally {
            scene.close()
        }
    }

    private fun slotOf(vararg kinds: String) =
        SlotContent(widgets = kinds.mapIndexed { i, k -> WidgetInstance(WidgetKind(k), "$k-$i") }, flow = FlowSpec.Column)

    @Test
    fun `a surface opened inside itself is refused and the rest still draws`() {
        val graph = LayoutGraph(surfaces = mapOf(surface to SurfaceLayout(slots = mapOf(slot to slotOf("self", "red")))))
        val refused = mutableListOf<SurfaceId>()
        val frame = render(graph, refused)
        assertEquals(listOf(surface), refused.distinct(), "the inner opening was refused, once per place")
        // The opener reserves its 100, refused inside; the red widget below it draws.
        assertEquals(Color.Red.toArgbInt(), frame.getColor(100, 125))
    }

    @Test
    fun `a loop through a second surface is caught too`() {
        val graph = LayoutGraph(
            surfaces = mapOf(
                surface to SurfaceLayout(slots = mapOf(slot to slotOf("other"))),
                other to SurfaceLayout(slots = mapOf(slot to slotOf("self"))),
            ),
        )
        val refused = mutableListOf<SurfaceId>()
        render(graph, refused)
        assertEquals(listOf(surface), refused.distinct())
    }

    @Test
    fun `the same surface side by side opens twice`() {
        val graph = LayoutGraph(
            surfaces = mapOf(
                surface to SurfaceLayout(slots = mapOf(slot to slotOf("other", "other"))),
                other to SurfaceLayout(slots = mapOf(slot to slotOf("red"))),
            ),
        )
        val refused = mutableListOf<SurfaceId>()
        val frame = render(graph, refused)
        assertEquals(emptyList(), refused, "two branches are not a loop")
        assertEquals(Color.Red.toArgbInt(), frame.getColor(100, 25))
        assertEquals(Color.Red.toArgbInt(), frame.getColor(100, 125))
    }

    private fun Color.toArgbInt(): Int = (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
}
