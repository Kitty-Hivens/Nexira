package hivens.look.cursor.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import hivens.look.manager.api.LookAxis

/** What a widget does where the pointer is, which decides the pointer. */
enum class CursorRole {
    /** Something that is pressed, picked or switched. */
    Pressable,

    /** Something that would be pressed and cannot be now. */
    Disabled,

    /** Something that leads out of the launcher, a web address. */
    Link,

    /** Text that can be selected. */
    Text,

    /** Something that can be picked up and dragged. */
    Draggable,

    /** Something that is being dragged. */
    Dragging,

    /** Something that would be dragged and cannot be now, or a place that takes nothing of what is dragged. */
    Refused,
}

/** An edge or a corner that is pulled to resize. */
enum class CursorEdge { North, South, West, East, NorthWest, NorthEast, SouthWest, SouthEast }

/**
 * The cursor axis: the pointer over what a widget draws, asked by what the widget does there, answered as
 * one [CursorAnswers] by the provider the person's choice names, and by [CursorAnswers.BASE] when no
 * provider is chosen for the axis. A widget takes its pointer with [cursor].
 *
 * A pack and the person's choice name it by [CursorApi.AXIS], and a provider answers it by naming that id
 * as its axis and [CursorApi.VERSION] as its contract.
 */
object Cursor : LookAxis<CursorAnswers>(CursorApi.AXIS, CursorApi.VERSION, CursorAnswers::class, CursorAnswers.BASE) {
    override val label: String = CursorModule.NAME

    /** See [CursorAnswers.pointer]. */
    @Composable
    @ReadOnlyComposable
    fun pointer(role: CursorRole): PointerIcon? = local.current.pointer(role)

    /** See [CursorAnswers.resize]. */
    @Composable
    @ReadOnlyComposable
    fun resize(edge: CursorEdge): PointerIcon? = local.current.resize(edge)
}

/**
 * The pointer the look has for [role] over this node, and the pointer around it where the look leaves the
 * role alone. A pointer asked deeper in the node, or later in the chain, wins over it.
 */
fun Modifier.cursor(role: CursorRole): Modifier = composed { pointed(Cursor.pointer(role)) }

/** The pointer the look has for pulling [edge], over this node. See [cursor]. */
fun Modifier.cursor(edge: CursorEdge): Modifier = composed { pointed(Cursor.resize(edge)) }

private fun Modifier.pointed(icon: PointerIcon?): Modifier = if (icon == null) this else pointerHoverIcon(icon)
