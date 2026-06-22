package hivens.ui.customization

import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

// Fine keyboard adjustment for a slider: hover it and press the arrow keys to
// nudge `value` by `step` (Right/Up increase, Left/Down decrease), clamped to
// `range`. Hover requests focus, so no click is needed -- the value tracks the
// cursor's slider, not the focus ring. Apply to a Box that wraps the Slider so
// the wrapper owns focus + keys while the Slider keeps the pointer drag.
@Composable
fun Modifier.sliderKeyboardAdjust(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
): Modifier {
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val live = rememberUpdatedState(value)
    LaunchedEffect(hovered) {
        if (hovered) runCatching { focus.requestFocus() }
    }
    return this
        .hoverable(interaction)
        .focusRequester(focus)
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            val delta = when (ev.key) {
                Key.DirectionRight, Key.DirectionUp -> step
                Key.DirectionLeft, Key.DirectionDown -> -step
                else -> return@onKeyEvent false
            }
            val next = (live.value + delta).coerceIn(range.start, range.endInclusive)
            if (next != live.value) onValueChange(next)
            true
        }
        .focusable(interactionSource = interaction)
}
