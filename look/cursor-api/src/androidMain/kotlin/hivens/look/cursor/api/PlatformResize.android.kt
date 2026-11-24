package hivens.look.cursor.api

import androidx.compose.ui.input.pointer.PointerIcon
import android.view.PointerIcon as PlatformIcon

internal actual fun platformResize(edge: CursorEdge): PointerIcon = ICONS.getValue(edge)

private val ICONS: Map<CursorEdge, PointerIcon> = CursorEdge.entries.associateWith { PointerIcon(it.type) }

private val CursorEdge.type: Int
    get() = when (this) {
        CursorEdge.North, CursorEdge.South -> PlatformIcon.TYPE_VERTICAL_DOUBLE_ARROW
        CursorEdge.West, CursorEdge.East -> PlatformIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        CursorEdge.NorthWest, CursorEdge.SouthEast -> PlatformIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
        CursorEdge.NorthEast, CursorEdge.SouthWest -> PlatformIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
    }
