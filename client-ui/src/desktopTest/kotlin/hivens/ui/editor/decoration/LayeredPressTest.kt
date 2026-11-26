package hivens.ui.editor.decoration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.EditFocus
import hivens.ui.editor.dnd.WidgetKey
import hivens.ui.editor.dnd.pressBelongsTo
import hivens.ui.editor.slotChromeModifier
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
import hivens.widget.model.SlotId
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A slot drawn over another, the centre's floating lane over the page, covers the whole page while what stands in it
 * draws nothing at rest. The hover outline named the widget on the page and a press went to the one in the lane. A press
 * is the named widget's now, wherever it is drawn.
 */
class LayeredPressTest {

    private val dir = Files.createTempDirectory("layered-press")
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
    private val root = NodePath(composition, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
    private val page = root.child("centre", SlotId("body"))
    private val lane = root.child("centre", SlotId("bottom"))

    @Test
    fun `a press on a widget of the page goes to it, not to the lane drawn over the page`() =
        assertPressTakenBy(smallOnPage = true, expected = page to "hero")

    /** The other way up: the small widget stands in the upper layer, over a widget of the page that fills it. */
    @Test
    fun `a press on a widget of the lane goes to it, not to the page's widget beneath`() =
        assertPressTakenBy(smallOnPage = false, expected = lane to "pill")

    /** The widget [expected] names is drawn small at the top start, the other fills its layer, and a press lands on the small one. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun assertPressTakenBy(smallOnPage: Boolean, expected: Pair<NodePath, String>) {
        val edit = DocumentEditor(DocumentStore(dir.resolve("interface.json"), { Document(start = composition) }), scope)
        val drag = DragController()
        val registry = DropTargetRegistry()
        val onPage = WidgetInstance(Descriptor.kind, "hero", JsonObject(emptyMap()))
        val inLane = WidgetInstance(Descriptor.kind, "pill", JsonObject(emptyMap()))
        val selected = mutableStateOf<NodePath?>(null)

        @Composable
        fun chrome(path: NodePath, instance: WidgetInstance, content: @Composable () -> Unit) = EditableWidgetChrome(
            path = path, index = 0, descriptor = Descriptor, instance = instance,
            controller = drag, editor = edit, registry = registry, focus = EditFocus(),
            flow = FlowSpec(), dropTarget = null, dropIndex = -1,
            onRemove = {}, onEditProps = {}, onCommitDrop = {}, content = content,
        )

        fun slot(path: NodePath) = slotChromeModifier(path, false, registry, selected, { selected.value = it }, { _, _ -> }, {})

        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            NxThemed(Themes.default, dark = true) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().then(slot(page))) {
                        chrome(page, onPage) { Box(if (smallOnPage) Modifier.size(100.dp) else Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.fillMaxSize().then(slot(lane))) {
                        chrome(lane, inLane) { Box(if (smallOnPage) Modifier.fillMaxSize() else Modifier.size(100.dp)) }
                    }
                }
            }
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            frame()
            assertEquals(WidgetKey(expected.first, expected.second), registry.widgetForPoint(Offset(50f, 50f)), "the outline is on the small widget")
            scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 50f))
            frame()
            for (y in 60..120 step 10) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(50f, y.toFloat()))
                frame()
            }
            val payload = assertNotNull(drag.active, "nothing was dragged").payload as DragPayload.ExistingWidget
            assertEquals(expected, payload.source to payload.instance.instanceId)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the named widget takes a press, and so does the uppermost where the named one cannot hear it`() {
        val hero = WidgetKey(page, "hero")
        val pill = WidgetKey(lane, "pill")
        assertTrue(pressBelongsTo(hero, hero))
        assertTrue(pressBelongsTo(pill, null), "nothing named yet: the uppermost, as before")
        assertFalse(pressBelongsTo(pill, hero), "a widget of another slot can hear it, so it is that one's")

        val sibling = WidgetKey(page, "card")
        assertTrue(pressBelongsTo(hero, sibling), "a sibling under it in the same slot never hears the press")
        assertTrue(pressBelongsTo(hero, WidgetKey(page.child("card", SlotId("body")), "inner")), "nor anything inside that sibling")
        assertFalse(pressBelongsTo(hero, WidgetKey(page.child("hero", SlotId("body")), "inner")), "what is inside this widget had the press first")
        assertFalse(pressBelongsTo(WidgetKey(page.child("hero", SlotId("body")), "inner"), hero), "and the container is outside the child's slot")
    }
}
