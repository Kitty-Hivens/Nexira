// The test plays the host: it makes the app level the condition is read from.
@file:OptIn(HostApi::class)

package hivens.ui.widgets

import hivens.ui.STANDING_ROOT
import hivens.ui.documentOf
import hivens.widget.api.LocalDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.themes.Themes
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxThemed
import hivens.widget.api.HiddenWidgetDecorator
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalHiddenWidget
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
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Bitmap
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A widget whose show condition does not hold is left out for use and, while somebody arranges, drawn
 * dimmed in its place under a mark naming the source it waits for, the way a widget on samples is.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HiddenWidgetTest {

    private val condition = SourceKey<Boolean>("test.shown", ScopeLevel.App)

    /** A white block, shown under [condition], that counts how often it was composed. */
    private class Block(val composed: IntArray) : WidgetDescriptor {
        override val kind = WidgetKind("test.block")
        override val displayName = "Block"
        override val removable = true
        override val shownWhen = "test.shown"

        @Composable
        override fun Render(instance: WidgetInstance) {
            composed[0]++
            Box(Modifier.size(200.dp, 120.dp).background(Color.White))
        }
    }

    private val graph = documentOf(SlotContent(widgets = listOf(WidgetInstance(kind = WidgetKind("test.block"), instanceId = "b"))))

    private class Drawn(val composed: Int, val frame: Bitmap, val texts: List<String>)

    /** Draws the slot with the condition answering [shown], through [hidden], and hands back what came of it. */
    private fun draw(shown: Boolean, hidden: HiddenWidgetDecorator?): Drawn {
        val composed = IntArray(1)
        val block = Block(composed)
        val registry = object : WidgetRegistry {
            override fun all() = mapOf(block.kind to block)
            override fun get(kind: WidgetKind) = block.takeIf { it.kind == kind }
        }
        val data = WidgetDataRegistry().apply { register(condition, flowSource(MutableStateFlow(shown))) }
        val app = WidgetScope.app(data, NoServices, Dispatchers.Unconfined, WidgetCommandRegistry())
        return onEventThread {
            val scene = ImageComposeScene(300, 200, density = Density(1f)) {
                ShippedLanguageProvider("en") {
                    NxThemed(Themes.default, dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalScope provides app,
                            LocalDocument provides graph,
                            LocalWidgetRegistry provides registry,
                            LocalHiddenWidget provides hidden,
                        ) {
                            Box(Modifier.fillMaxSize()) { SlotRenderer(STANDING_ROOT) }
                        }
                    }
                }
            }
            try {
                val image = scene.render(scene.settle(6))
                val texts = scene.semanticsOwners.asSequence()
                    .flatMap { it.rootSemanticsNode.withDescendants() }
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }
                    .map { it.text }
                    .toList()
                try {
                    Drawn(composed[0], Bitmap.makeFromImage(image), texts)
                } finally {
                    image.close()
                }
            } finally {
                scene.close()
            }
        }
    }

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

    private fun Bitmap.alphaAt(x: Int, y: Int): Int {
        val info = imageInfo
        val pixels = assertNotNull(readPixels(info, info.minRowBytes, 0, 0))
        return pixels[y * info.minRowBytes + x * 4 + 3].toInt() and 0xFF
    }

    private fun Bitmap.inked(): Int {
        val info = imageInfo
        val pixels = assertNotNull(readPixels(info, info.minRowBytes, 0, 0))
        return (pixels.indices step 4).count { pixels[it + 3] != 0.toByte() }
    }

    @Test
    fun `while somebody arranges, the hidden widget is drawn dimmed under a mark naming its condition`() {
        val drawn = draw(shown = false, hidden = HiddenWidget)
        try {
            assertTrue(drawn.composed > 0, "the block was not drawn at all")
            val dimmed = drawn.frame.alphaAt(20, 100)
            assertTrue(dimmed in 90..140, "the block is dimmed, not drawn at full strength ($dimmed)")
            val marked = (150..196).flatMap { x -> (6..20).map { y -> drawn.frame.alphaAt(x, y) } }.max()
            assertTrue(marked > 200, "a mark is drawn over the dimmed block ($marked)")
            assertTrue(drawn.texts.any { "test.shown" in it }, "the mark names the condition: ${drawn.texts}")
        } finally {
            drawn.frame.close()
        }
    }

    @Test
    fun `for use the hidden widget is not composed and nothing stands in its place`() {
        val drawn = draw(shown = false, hidden = null)
        try {
            assertEquals(0, drawn.composed, "the block was composed")
            assertEquals(0, drawn.frame.inked(), "something was drawn in its place")
        } finally {
            drawn.frame.close()
        }
    }

    @Test
    fun `a widget whose condition holds is drawn as it is, arranging or not`() {
        for (hidden in listOf(HiddenWidget, null)) {
            val drawn = draw(shown = true, hidden = hidden)
            try {
                assertEquals(WHITE, drawn.frame.getColor(20, 100), "the block is drawn at full strength")
                assertEquals(WHITE, drawn.frame.getColor(180, 12), "and with no mark over it")
                assertTrue(drawn.texts.none { "test.shown" in it }, "no mark: ${drawn.texts}")
            } finally {
                drawn.frame.close()
            }
        }
    }

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
        const val WHITE: Int = -1
    }
}
