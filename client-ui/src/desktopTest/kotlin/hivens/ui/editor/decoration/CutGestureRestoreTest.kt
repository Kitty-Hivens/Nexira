package hivens.ui.editor.decoration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A free placement drag writes the offset on every frame. Cut short, the editor
 * closing under it say, it used to keep wherever the pointer had got to. It puts
 * the widget back where it began now, and a drag let go keeps its move.
 */
class CutGestureRestoreTest {

    private val dir = Files.createTempDirectory("cut-gesture")
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

    private val composition = CompositionName("test")
    private val path = NodePath(composition, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
    private val start = Placement(x = 10f, y = 10f)
    private val instance = WidgetInstance(Descriptor.kind, "i1", JsonObject(emptyMap()), placement = start)

    private fun store(): DocumentStore {
        val root = SlotContent(widgets = listOf(instance), flow = null)
        val config = Config(layouts = listOf(ConfigLayout(LayoutId("main"), root)))
        val document = Document(start = composition, compositions = mapOf(composition to Composition(start = ConfigId("home"), configs = mapOf(ConfigId("home") to config))))
        return DocumentStore(dir.resolve("interface.json"), { document }).also { it.takeOwn() }
    }

    private fun DocumentStore.placement(): Placement? =
        document.value.contentAt(path)?.widgets?.single()?.placement

    private enum class End { LetGo, ChromeGone, Escape }

    /** Presses the widget, drags it, and then lets go, takes the chrome away mid-drag, or cuts it short as Escape does. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun drag(store: DocumentStore, edit: DocumentEditor, end: End, drag: DragController = DragController()) {
        var shown by mutableStateOf(true)
        val registry = DropTargetRegistry()
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                Box(Modifier.fillMaxSize()) {
                    if (shown) {
                        EditableWidgetChrome(
                            path = path, index = 0, descriptor = Descriptor, instance = instance,
                            controller = drag, editor = edit, registry = registry, focus = EditFocus(),
                            flow = null, dropTarget = null, dropIndex = -1,
                            onRemove = {}, onEditProps = {}, onCommitDrop = {},
                        ) {
                            Box(Modifier.size(100.dp))
                        }
                    }
                }
            }
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 50f))
            frame()
            for (step in 1..5) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(50f + step * 10f, 50f + step * 8f))
                frame()
            }
            runBlocking { edit.awaitWrites() }
            assertNotEquals(start, store.placement(), "the drag never moved the widget, so the test proved nothing")

            assertTrue(drag.gestureUnderWay, "a drag under way is what Escape asks about")
            when (end) {
                End.LetGo -> scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 90f))
                End.ChromeGone -> {
                    shown = false
                    Snapshot.sendApplyNotifications()
                }
                End.Escape -> {
                    drag.cutGesture()
                    // The next frame of the drag is where it stops, and the pointer still held moves nothing after it.
                    scene.sendPointerEvent(PointerEventType.Move, Offset(120f, 100f))
                    frame()
                    scene.sendPointerEvent(PointerEventType.Move, Offset(140f, 110f))
                    frame()
                    scene.sendPointerEvent(PointerEventType.Release, Offset(140f, 110f))
                }
            }
            frame()
            frame()
            runBlocking { edit.awaitWrites() }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a drag cut short puts the widget back and leaves nothing to undo`() {
        val store = store()
        val edit = DocumentEditor(store, scope)
        edit.reportEditing(true)

        drag(store, edit, End.ChromeGone)

        assertEquals(start, store.placement())
        assertFalse(edit.canUndo, "undo would only lead back into the move that was abandoned")
    }

    @Test
    fun `Escape mid-drag puts the widget back, ends the gesture and leaves nothing to undo`() {
        val store = store()
        val edit = DocumentEditor(store, scope)
        edit.reportEditing(true)
        val controller = DragController()

        drag(store, edit, End.Escape, controller)

        assertEquals(start, store.placement())
        assertFalse(edit.canUndo)
        assertFalse(controller.gestureUnderWay, "Escape goes back to leaving the editor")
    }

    @Test
    fun `a drag let go keeps its move as one step`() {
        val store = store()
        val edit = DocumentEditor(store, scope)
        edit.reportEditing(true)

        drag(store, edit, End.LetGo)

        assertEquals(Placement(x = 60f, y = 50f), store.placement())
        assertTrue(edit.canUndo)
    }
}
