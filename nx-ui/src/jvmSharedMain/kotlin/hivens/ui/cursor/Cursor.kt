package hivens.ui.cursor

import androidx.compose.ui.Modifier
import hivens.look.cursor.api.cursor as axisCursor

/** What a widget does where the pointer is, see [hivens.look.cursor.api.CursorRole]. */
typealias CursorRole = hivens.look.cursor.api.CursorRole

/** An edge or a corner that is pulled to resize, see [hivens.look.cursor.api.CursorEdge]. */
typealias CursorEdge = hivens.look.cursor.api.CursorEdge

/** The pointer the look has for [role] over this node. A pointer asked deeper, or later in the chain, wins over it. */
fun Modifier.cursor(role: CursorRole): Modifier = axisCursor(role)

/** The pointer the look has for pulling [edge], over this node. */
fun Modifier.cursor(edge: CursorEdge): Modifier = axisCursor(edge)

/** The pointer over something that is dragged: an open hand over it, a closed one while [dragging], a refusal where it cannot move now. */
fun Modifier.dragCursor(dragging: Boolean, enabled: Boolean = true): Modifier = cursor(
    when {
        !enabled -> CursorRole.Refused
        dragging -> CursorRole.Dragging
        else -> CursorRole.Draggable
    },
)
