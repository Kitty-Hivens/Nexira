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
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.theme.NxTheme
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.LayoutGraph
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.traverse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    private val path = SlotPath(SurfaceId("s"), SlotId("main"))
    private val start = Placement(x = 10f, y = 10f)
    private val instance = WidgetInstance(Descriptor.kind, "i1", JsonObject(emptyMap()), placement = start)

    private fun repo(): LayoutGraphRepository {
        val repo = LayoutGraphRepository(dir.resolve("layout.json"), Json, scope) { LayoutGraph.EMPTY }
        runBlocking {
            repo.update {
                LayoutGraph(surfaces = mapOf(
                    SurfaceId("s") to SurfaceLayout(slots = mapOf(
                        SlotId("main") to SlotContent(widgets = listOf(instance), flow = null),
                    )),
                ))
            }
        }
        return repo
    }

    private fun LayoutGraphRepository.placement(): Placement? =
        value().traverse(path)?.widgets?.single()?.placement

    /** Presses the widget, drags it, and then lets go or takes the chrome away mid-drag. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun drag(repo: LayoutGraphRepository, edit: EditModeController, letGo: Boolean) {
        var shown by mutableStateOf(true)
        val drag = DragController()
        val registry = DropTargetRegistry()
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    if (shown) {
                        EditableWidgetChrome(
                            path = path, index = 0, descriptor = Descriptor, instance = instance,
                            controller = drag, editController = edit, registry = registry,
                            flow = null, onRemove = {}, onEditProps = {}, onCommitDrop = {},
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
            assertNotEquals(start, repo.placement(), "the drag never moved the widget, so the test proved nothing")

            if (letGo) {
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 90f))
            } else {
                shown = false
                Snapshot.sendApplyNotifications()
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
        val repo = repo()
        val edit = EditModeController(repo, scope)
        edit.reportEditing(true)

        drag(repo, edit, letGo = false)

        assertEquals(start, repo.placement())
        assertFalse(edit.canUndo, "undo would only lead back into the move that was abandoned")
    }

    @Test
    fun `a drag let go keeps its move as one step`() {
        val repo = repo()
        val edit = EditModeController(repo, scope)
        edit.reportEditing(true)

        drag(repo, edit, letGo = true)

        assertEquals(Placement(x = 60f, y = 50f), repo.placement())
        assertTrue(edit.canUndo)
    }
}
