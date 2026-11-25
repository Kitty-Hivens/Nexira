package hivens.ui.press

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorAnswers
import hivens.look.cursor.api.CursorEdge
import hivens.ui.cursor.CursorRole
import hivens.ui.cursor.cursor
import hivens.ui.cursor.dragCursor
import hivens.ui.nx.NxButton
import hivens.ui.nx.nxDraggable
import hivens.look.preview.NeutralTheme
import hivens.ui.theme.NxThemed
import org.jetbrains.skia.Surface
import androidx.compose.ui.graphics.asComposeCanvas
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertSame

/** The pointer the window is told to show over what nx-ui draws, under a look whose every role has its own. */
@OptIn(InternalComposeUiApi::class)
class PressCursorTest {

    private val pointers = CursorRole.entries.associateWith { Named(it.name) }
    private val edges = CursorEdge.entries.associateWith { Named(it.name) }
    private val look = CursorAnswers.build {
        pointer = { pointers.getValue(it) }
        resize = { edges.getValue(it) }
    }

    private class Named(private val name: String) : PointerIcon {
        override fun toString() = name
    }

    /** The pointer shown over the middle of [content], a square of 40, under [answers]. */
    private fun over(answers: CursorAnswers = look, content: @Composable () -> Unit): PointerIcon? {
        var shown: PointerIcon? = PointerIcon.Crosshair
        val context = object : PlatformContext by PlatformContext.Empty() {
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                shown = pointerIcon
            }
        }
        val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = recomposer, density = Density(1f), size = IntSize(40, 40), platformContext = context)
        val surface = Surface.makeRasterN32Premul(40, 40)
        fun frame(at: Long) {
            Snapshot.sendApplyNotifications()
            recomposer.performFrame(at)
            scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
        }
        try {
            scene.setContent { CompositionLocalProvider(Cursor.local provides answers, content = content) }
            repeat(2) { frame(it * 16_000_000L) }
            scene.sendPointerEvent(PointerEventType.Enter, Offset(20f, 20f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 20f))
            frame(48_000_000L)
            return shown
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }

    private fun assertOver(role: CursorRole, content: @Composable () -> Unit) = assertSame<Any?>(pointers.getValue(role), over(content = content))

    private val square = Modifier.size(40.dp)

    @Test
    fun `every press asks the look, usable or not`() {
        assertOver(CursorRole.Pressable) { Box(square.clickable { }) }
        assertOver(CursorRole.Disabled) { Box(square.clickable(enabled = false) { }) }
        assertOver(CursorRole.Pressable) { Box(square.clickable(null, null) { }) }
        assertOver(CursorRole.Pressable) { Box(square.combinedClickable(onLongClick = {}) { }) }
        assertOver(CursorRole.Pressable) { Box(square.combinedClickable(null, null) { }) }
        assertOver(CursorRole.Pressable) { Box(square.selectable(selected = true) { }) }
        assertOver(CursorRole.Pressable) { Box(square.selectable(true, null, null) { }) }
        assertOver(CursorRole.Disabled) { Box(square.toggleable(value = true, enabled = false) { }) }
        assertOver(CursorRole.Pressable) { Box(square.toggleable(true, null, null) { }) }
    }

    @Test
    fun `Material's buttons ask the look, and so does nx's own`() {
        assertOver(CursorRole.Pressable) { NxThemed(NeutralTheme) { Button({}, square) { } } }
        assertOver(CursorRole.Disabled) { NxThemed(NeutralTheme) { OutlinedButton({}, square, enabled = false) { } } }
        assertOver(CursorRole.Pressable) { NxThemed(NeutralTheme) { TextButton({}, square) { } } }
        assertOver(CursorRole.Pressable) { NxThemed(NeutralTheme) { IconButton({}, square) { Text("x") } } }
        assertOver(CursorRole.Pressable) { NxThemed(NeutralTheme) { NxButton("x", {}, square) } }
        assertOver(CursorRole.Disabled) { NxThemed(NeutralTheme) { NxButton("x", {}, square, enabled = false) } }
    }

    @Test
    fun `a pointer a button's own modifier asks for wins over the press`() {
        assertOver(CursorRole.Link) { NxThemed(NeutralTheme) { OutlinedButton({}, square.cursor(CursorRole.Link)) { } } }
    }

    @Test
    fun `a press inside something to drag is a press`() {
        assertOver(CursorRole.Pressable) { Box(square.cursor(CursorRole.Draggable)) { Box(square.clickable { }) } }
    }

    @Test
    fun `an edge, something to drag and something that cannot be dragged each ask for their own`() {
        assertSame<Any?>(edges.getValue(CursorEdge.SouthWest), over { Box(square.cursor(CursorEdge.SouthWest)) })
        assertOver(CursorRole.Draggable) { Box(square.dragCursor(dragging = false)) }
        assertOver(CursorRole.Dragging) { Box(square.dragCursor(dragging = true)) }
        assertOver(CursorRole.Refused) { Box(square.dragCursor(dragging = true, enabled = false)) }
        assertOver(CursorRole.Draggable) { Box(square.nxDraggable(enabled = true, onDrag = {})) }
        assertOver(CursorRole.Refused) { Box(square.nxDraggable(enabled = false, onDrag = {})) }
    }

    @Test
    fun `a role the look leaves alone keeps the pointer around it`() {
        val quiet = CursorAnswers.build { pointer = { role -> pointers.getValue(role).takeIf { role == CursorRole.Text } } }
        val text = over(quiet) { Box(square.cursor(CursorRole.Text)) { Box(square.dragCursor(dragging = false)) } }
        assertSame<Any?>(pointers.getValue(CursorRole.Text), text)
    }
}
