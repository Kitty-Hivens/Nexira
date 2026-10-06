package hivens.ui.editor.decoration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.theme.NxTheme
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A widget being dragged is one widget. The ghost that follows the pointer used to
 * compose it a second time, and a second live copy under the same instance id took
 * over whatever the widget registered by that id and withdrew it on the drop.
 */
class DragGhostLiveCopyTest {

    private val dir = Files.createTempDirectory("drag-ghost")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private object Descriptor : WidgetDescriptor {
        override val kind = WidgetKind("test.probe")
        override val displayName = "probe"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = Unit
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `dragging a widget does not compose a second copy of it`() {
        val repo = LayoutGraphRepository(dir.resolve("layout.json"), Json, scope) { LayoutGraph.EMPTY }
        val edit = EditModeController(repo, scope)
        val drag = DragController()
        val registry = DropTargetRegistry()
        val path = SlotPath(SurfaceId("s"), SlotId("main"))
        val instance = WidgetInstance(Descriptor.kind, "i1", JsonObject(emptyMap()))

        var live = 0
        var mostAtOnce = 0
        var sawDrag = false

        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        EditableWidgetChrome(
                            path = path, index = 0, descriptor = Descriptor, instance = instance,
                            controller = drag, editController = edit, registry = registry,
                            flow = FlowSpec(), onRemove = {}, onEditProps = {}, onCommitDrop = {},
                        ) {
                            DisposableEffect(Unit) {
                                live++
                                mostAtOnce = maxOf(mostAtOnce, live)
                                onDispose { live-- }
                            }
                            Box(Modifier.size(100.dp).background(Color.Red))
                        }
                    }
                    drag.active?.let { sawDrag = true; it.ghost() }
                }
            }
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 50f))
            frame()
            for (y in 55..150 step 5) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(50f, y.toFloat()))
                frame()
            }
            assertTrue(sawDrag, "the drag never started, so the test proved nothing")
            assertEquals(1, mostAtOnce, "copies of the widget composed at once during the drag")
        } finally {
            scene.close()
        }
    }
}
