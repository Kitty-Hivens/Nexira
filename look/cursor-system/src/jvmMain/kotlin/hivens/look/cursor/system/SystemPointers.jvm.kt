package hivens.look.cursor.system

import androidx.compose.ui.input.pointer.PointerIcon
import hivens.look.cursor.api.CursorRole
import java.awt.GraphicsEnvironment

internal actual fun systemPointers(): SystemPointers = DesktopPointers(if (GraphicsEnvironment.isHeadless()) ({ null }) else XCursorTheme::pointer)

/**
 * The desktop's pointers. The toolkit's arrow, hand and text bar are the system's own, themed as it is. It
 * knows no open or closed hand and no refusal, so those are asked of [themed] by the name a cursor theme
 * has them under, and left alone where it has none, as on Windows and macOS.
 */
internal class DesktopPointers(private val themed: (name: String) -> PointerIcon?) : SystemPointers {
    private val grab by lazy { themed("grab") }
    private val grabbing by lazy { themed("grabbing") }
    private val refused by lazy { themed("not-allowed") }

    override fun pointer(role: CursorRole): PointerIcon? = when (role) {
        CursorRole.Pressable, CursorRole.Disabled -> PointerIcon.Default
        CursorRole.Link -> PointerIcon.Hand
        CursorRole.Text -> PointerIcon.Text
        CursorRole.Draggable -> grab
        // A theme with one of the two hands alone would change the pointer halfway through a drag.
        CursorRole.Dragging -> if (grab == null) null else grabbing
        CursorRole.Refused -> refused
    }
}
