package hivens.ui.widgets

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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SourceKey
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A widget standing where its page's data is not: drawn on its samples, dimmed and
 * marked while somebody arranges, and not drawn at all for use, so a sample is never
 * shown as the person's own data.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SampledWidgetTest {

    private val pageKey = SourceKey<String>("test.page", ScopeLevel.Page)

    private val data = WidgetDataRegistry().apply { declare(pageKey) { "sample" } }

    private val app: WidgetScope = koinApplication {
        modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, WidgetCommandRegistry()) } })
    }.koin.get()

    /** A white block that says what it was drawn on. */
    private class Block(val read: MutableList<String?>) : WidgetDescriptor {
        override val kind = WidgetKind("test.block")
        override val displayName = "Block"
        override val removable = true
        override val needs = listOf("test.page")

        @Composable
        override fun Render(instance: WidgetInstance) {
            read += rememberScopedSource(SourceKey<String>("test.page", ScopeLevel.Page)).valueOrNull()
            Box(Modifier.size(200.dp, 120.dp).background(Color.White))
        }
    }

    private val graph = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(widgets = listOf(WidgetInstance(kind = WidgetKind("test.block"), instanceId = "b")))),
            ),
        ),
    )

    private fun editing(): EditModeState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = Files.createTempDirectory("sampled-widget-test")
        val repo = LayoutGraphRepository(dir.resolve("layout-graph.json"), Json { ignoreUnknownKeys = true }, scope) { LayoutGraph.EMPTY }
        return EditModeState.On(SurfaceId("s"), EditModeController(repo, scope))
    }

    /** Draws the slot on a surface with no page above it, and hands back what the block read and the frame's pixels. */
    private fun draw(mode: EditModeState, data: WidgetDataRegistry = this.data, app: WidgetScope = this.app): Pair<List<String?>, Bitmap> {
        val read = ArrayList<String?>()
        val block = Block(read)
        val registry = object : WidgetRegistry {
            override fun all() = mapOf(block.kind to block)
            override fun get(kind: WidgetKind) = block.takeIf { it.kind == kind }
        }
        val bitmap = onEventThread {
            val scene = ImageComposeScene(300, 200, density = Density(1f)) {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides WidgetCommandRegistry(),
                            LocalScope provides app,
                            LocalLayoutGraph provides graph,
                            LocalWidgetRegistry provides registry,
                            LocalMisplacedWidget provides MisplacedWidget,
                            LocalEditMode provides mode,
                        ) {
                            ChildLevel(ScopeLevel.Composition) {
                                Box(Modifier.fillMaxSize()) { SlotRenderer(SurfaceId("s"), SlotId("main")) }
                            }
                        }
                    }
                }
            }
            try {
                val image = scene.render(scene.settle(6))
                try {
                    Bitmap.makeFromImage(image)
                } finally {
                    image.close()
                }
            } finally {
                scene.close()
            }
        }
        return read to bitmap
    }

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
    fun `in the editor the widget is drawn on its sample, dimmed, under a mark`() {
        val (read, frame) = draw(editing())
        try {
            assertEquals("sample", read.lastOrNull(), "the block read the declared sample")
            val dimmed = frame.alphaAt(20, 100)
            assertTrue(dimmed in 90..140, "the block is dimmed, not drawn at full strength ($dimmed)")
            // The mark sits in the block's top right corner and is drawn at full strength.
            val marked = (150..196).flatMap { x -> (6..20).map { y -> frame.alphaAt(x, y) } }.max()
            assertTrue(marked > 200, "a mark is drawn over the dimmed block ($marked)")
        } finally {
            frame.close()
        }
    }

    @Test
    fun `for use the widget is not drawn at all, and its sample is never read`() {
        val (read, frame) = draw(EditModeState.Off)
        try {
            assertTrue(read.isEmpty(), "the block was composed: $read")
            assertEquals(0, frame.inked(), "nothing stands in its place, not even a card")
        } finally {
            frame.close()
        }
    }

    @Test
    fun `with no sample the editor falls back to the warning card`() {
        val bare = WidgetDataRegistry().apply { declare(pageKey) }
        val scope: WidgetScope = koinApplication {
            modules(module { single { WidgetScope.app(bare, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, WidgetCommandRegistry()) } })
        }.koin.get()
        assertNull(scope.sampleOf("test.page"))
        val (read, frame) = draw(editing(), bare, scope)
        try {
            assertTrue(read.isEmpty(), "with nothing to draw it on, the block is not composed: $read")
            assertTrue(frame.inked() > 200, "the card naming what is missing is drawn instead")
        } finally {
            frame.close()
        }
    }
}
