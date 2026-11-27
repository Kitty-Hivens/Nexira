package hivens.ui.editor.dnd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A drag out of the palette drops what it carries when let go, and nothing once Escape has cut it. */
class DragSourceCutTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun dragOut(cut: Boolean): List<Offset> {
        val controller = DragController()
        val dropped = ArrayList<Offset>()
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            Box(
                Modifier.size(100.dp).dragSource(
                    controller = controller,
                    payload = DragPayload.PaletteWidget(WidgetKind("test.probe")),
                    widgetBoundsProvider = { Rect(0f, 0f, 100f, 100f) },
                    ghost = {},
                    onDragEnd = { dropped += it },
                ),
            )
        }
        try {
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            frame()
            scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 50f))
            frame()
            for (step in 1..5) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(50f + step * 20f, 50f))
                frame()
            }
            if (cut) controller.cutGesture()
            scene.sendPointerEvent(PointerEventType.Release, Offset(150f, 50f))
            frame()
            assertNull(controller.active, "the ghost is gone either way")
            assertFalse(controller.gestureUnderWay)
        } finally {
            scene.close()
        }
        return dropped
    }

    @Test
    fun `a drag let go drops where it was let go`() {
        assertEquals(listOf(Offset(150f, 50f)), dragOut(cut = false))
    }

    @Test
    fun `a drag cut by Escape and let go where it stands drops nothing`() {
        assertEquals(emptyList(), dragOut(cut = true))
    }
}
