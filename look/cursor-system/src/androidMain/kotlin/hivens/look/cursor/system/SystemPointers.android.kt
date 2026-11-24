package hivens.look.cursor.system

import androidx.compose.ui.input.pointer.PointerIcon
import hivens.look.cursor.api.CursorRole
import android.view.PointerIcon as PlatformIcon

internal actual fun systemPointers(): SystemPointers = AndroidPointers

private object AndroidPointers : SystemPointers {
    private val grab = PointerIcon(PlatformIcon.TYPE_GRAB)
    private val grabbing = PointerIcon(PlatformIcon.TYPE_GRABBING)
    private val refused = PointerIcon(PlatformIcon.TYPE_NO_DROP)

    override fun pointer(role: CursorRole): PointerIcon = when (role) {
        CursorRole.Pressable, CursorRole.Disabled -> PointerIcon.Default
        CursorRole.Link -> PointerIcon.Hand
        CursorRole.Text -> PointerIcon.Text
        CursorRole.Draggable -> grab
        CursorRole.Dragging -> grabbing
        CursorRole.Refused -> refused
    }
}
