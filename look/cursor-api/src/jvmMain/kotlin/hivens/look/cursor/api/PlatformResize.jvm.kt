package hivens.look.cursor.api

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor as AwtCursor

internal actual fun platformResize(edge: CursorEdge): PointerIcon = ICONS.getValue(edge)

private val ICONS: Map<CursorEdge, PointerIcon> = CursorEdge.entries.associateWith { PointerIcon(AwtCursor.getPredefinedCursor(it.awt)) }

private val CursorEdge.awt: Int
    get() = when (this) {
        CursorEdge.North -> AwtCursor.N_RESIZE_CURSOR
        CursorEdge.South -> AwtCursor.S_RESIZE_CURSOR
        CursorEdge.West -> AwtCursor.W_RESIZE_CURSOR
        CursorEdge.East -> AwtCursor.E_RESIZE_CURSOR
        CursorEdge.NorthWest -> AwtCursor.NW_RESIZE_CURSOR
        CursorEdge.NorthEast -> AwtCursor.NE_RESIZE_CURSOR
        CursorEdge.SouthWest -> AwtCursor.SW_RESIZE_CURSOR
        CursorEdge.SouthEast -> AwtCursor.SE_RESIZE_CURSOR
    }
