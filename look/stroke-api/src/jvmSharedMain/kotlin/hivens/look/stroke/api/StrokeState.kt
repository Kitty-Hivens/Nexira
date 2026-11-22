package hivens.look.stroke.api

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue

/**
 * The state a widget asks a stroke in: the states Compose reports for something a person points at,
 * focuses, presses or drags ([hovered], [focused], [pressed], [dragged]), and the two a widget knows of
 * itself, whether it takes input at all ([enabled]) and whether it is the chosen one ([selected]).
 *
 * The set is closed. A provider answers every stroke from these six and nothing else, so a widget that
 * passes them has said everything a stroke can depend on, and one that passes fewer is drawn as though
 * the rest were at rest. They combine: a selected tab can be hovered and pressed at once.
 */
@Immutable
class StrokeState(
    val hovered: Boolean = false,
    val focused: Boolean = false,
    val pressed: Boolean = false,
    val dragged: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is StrokeState && other.hovered == hovered && other.focused == focused && other.pressed == pressed &&
            other.dragged == dragged && other.enabled == enabled && other.selected == selected

    override fun hashCode(): Int = listOf(hovered, focused, pressed, dragged, enabled, selected).hashCode()

    override fun toString(): String =
        "StrokeState(hovered=$hovered, focused=$focused, pressed=$pressed, dragged=$dragged, enabled=$enabled, selected=$selected)"

    companion object {
        /** Nothing pointed at, focused, pressed or dragged, enabled and not chosen. */
        val Rest: StrokeState = StrokeState()
    }
}

/**
 * The state of what this source reports interactions for: hovered, focused, pressed and dragged as
 * Compose collects them, with [enabled] and [selected] as the widget knows them. Recomposes the caller
 * whenever one of them changes.
 */
@Composable
fun InteractionSource.collectStrokeState(enabled: Boolean = true, selected: Boolean = false): StrokeState {
    val hovered by collectIsHoveredAsState()
    val focused by collectIsFocusedAsState()
    val pressed by collectIsPressedAsState()
    val dragged by collectIsDraggedAsState()
    return StrokeState(hovered, focused, pressed, dragged, enabled, selected)
}
