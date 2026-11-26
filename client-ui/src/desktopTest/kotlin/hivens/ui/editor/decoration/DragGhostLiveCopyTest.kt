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
import hivens.test.stopThenDelete
import hivens.themes.Themes
import hivens.ui.editor.DocumentEditor
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.EditFocus
import hivens.ui.layout.DocumentStore
import hivens.ui.theme.NxThemed
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun tearDown() = stopThenDelete(scope, dir)

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
        val composition = CompositionName("test")
        val edit = DocumentEditor(DocumentStore(dir.resolve("interface.json"), { Document(start = composition) }), scope)
        val drag = DragController()
        val registry = DropTargetRegistry()
        val path = NodePath(composition, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
        val instance = WidgetInstance(Descriptor.kind, "i1", JsonObject(emptyMap()))

        var live = 0
        var mostAtOnce = 0
        var sawDrag = false

        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        EditableWidgetChrome(
                            path = path, index = 0, descriptor = Descriptor, instance = instance,
                            controller = drag, editor = edit, registry = registry, focus = EditFocus(),
                            flow = FlowSpec(), dropTarget = null, dropIndex = -1,
                            onRemove = {}, onEditProps = {}, onCommitDrop = {},
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

    /** A fast first move crosses the slop a long way from the press, and the ghost has to stay where it was taken. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the ghost is held where the widget was pressed, however far the first move jumps`() {
        val composition = CompositionName("test")
        val edit = DocumentEditor(DocumentStore(dir.resolve("interface.json"), { Document(start = composition) }), scope)
        val drag = DragController()
        val path = NodePath(composition, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
        val instance = WidgetInstance(Descriptor.kind, "i1", JsonObject(emptyMap()))
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                Column {
                    EditableWidgetChrome(
                        path = path, index = 0, descriptor = Descriptor, instance = instance,
                        controller = drag, editor = edit, registry = DropTargetRegistry(), focus = EditFocus(),
                        flow = FlowSpec(), dropTarget = null, dropIndex = -1,
                        onRemove = {}, onEditProps = {}, onCommitDrop = {},
                    ) {
                        Box(Modifier.size(100.dp))
                    }
                }
            }
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            scene.sendPointerEvent(PointerEventType.Press, Offset(30f, 40f))
            frame()
            scene.sendPointerEvent(PointerEventType.Move, Offset(30f, 240f))
            frame()
            val active = assertNotNull(drag.active, "the drag never started, so the test proved nothing")
            assertEquals(Offset(0f, 200f), active.pointerInWindow - active.pickupOffset, "the ghost's corner, moved as far as the pointer")
        } finally {
            scene.close()
        }
    }
}
