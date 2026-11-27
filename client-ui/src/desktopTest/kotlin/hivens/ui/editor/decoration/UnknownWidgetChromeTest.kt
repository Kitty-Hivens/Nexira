package hivens.ui.editor.decoration

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import hivens.test.stopThenDelete
import hivens.themes.Themes
import hivens.ui.editor.DocumentEditor
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.EditFocus
import hivens.ui.editor.dnd.WidgetKey
import hivens.ui.layout.DocumentStore
import hivens.ui.theme.NxThemed
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalUnknownWidgetDecorator
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.UnknownWidgetDecorator
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A widget whose module is not loaded stands in the editor in the chrome every widget wears: the pointer finds it, and
 * a drag moves it, with what the record carries kept as it was.
 */
class UnknownWidgetChromeTest {

    private val dir = Files.createTempDirectory("unknown-widget-chrome")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    private val composition = CompositionName("test")
    private val path = NodePath(composition, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
    private val start = Placement(x = 10f, y = 10f, width = 160f, height = 60f)
    private val gone = WidgetInstance(WidgetKind("gone.module.widget"), "u1", placement = start)

    private val nothing = object : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = emptyMap()
        override fun get(kind: WidgetKind): WidgetDescriptor? = null
    }

    private fun store(): DocumentStore {
        val root = SlotContent(widgets = listOf(gone), flow = null)
        val config = Config(layouts = listOf(ConfigLayout(LayoutId("main"), root)))
        val document = Document(start = composition, compositions = mapOf(composition to Composition(start = ConfigId("home"), configs = mapOf(ConfigId("home") to config))))
        return DocumentStore(dir.resolve("interface.json"), { document }).also { it.takeOwn() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a widget of a kind nobody has is found by the pointer and moved by a drag`() {
        val store = store()
        val edit = DocumentEditor(store, scope).also { it.reportEditing(true) }
        val registry = DropTargetRegistry()
        val controller = DragController()
        // As the editor's host wires it: the stand-in declaration, no panel to open.
        val unknown: UnknownWidgetDecorator = { at, index, instance ->
            EditableWidgetChrome(
                path = at, index = index, descriptor = UnknownWidget(instance.kind), instance = instance,
                controller = controller, editor = edit, registry = registry, focus = EditFocus(),
                flow = null, dropTarget = null, dropIndex = -1,
                onRemove = {}, onEditProps = null, onCommitDrop = {},
            ) { UnsupportedWidgetPlaceholder(instance) }
        }
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                val document by store.document.collectAsState()
                CompositionLocalProvider(
                    LocalDocument provides document,
                    LocalWidgetRegistry provides nothing,
                    LocalUnknownWidgetDecorator provides unknown,
                ) {
                    SlotRenderer(path, Modifier.fillMaxSize())
                }
            }
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            frame()
            assertEquals(WidgetKey(path, "u1"), registry.widgetForPoint(Offset(40f, 30f)), "the chrome reports where it is")

            scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 30f))
            frame()
            for (step in 1..5) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(40f + step * 10f, 30f + step * 6f))
                frame()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(90f, 60f))
            frame()
            frame()
            runBlocking { edit.awaitWrites() }

            val moved = store.document.value.contentAt(path)!!.widgets.single()
            assertEquals(start.copy(x = 60f, y = 40f), moved.placement)
            assertEquals(gone.kind, moved.kind, "the record is the same widget, only moved")
            assertTrue(edit.canUndo)
        } finally {
            scene.close()
        }
    }
}
