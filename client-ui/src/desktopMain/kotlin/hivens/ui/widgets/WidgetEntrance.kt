package hivens.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.theme.Motion
import hivens.widget.api.WidgetEntrance
import hivens.widget.model.Entrance
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The arrival the kernel asks for, drawn with the interface's motion scale.
 *
 * Each character takes its timing from the role that already describes that kind
 * of movement: a fade is [Motion.fade], a rise is content opening and takes
 * [Motion.reveal], a settle is something landing and takes [Motion.emphasis] with
 * its overshoot. Nothing here picks a duration, so the arrivals move with the rest
 * of the interface when the scale does.
 *
 * Widgets in one slot come up one after another, by their place in it, so a
 * surface assembles rather than appears. A pinned delay replaces that place.
 *
 * Draw-phase only: the widget is laid out at its final size and position from the
 * first frame and the arrival is alpha, offset and scale over it, so nothing around
 * it moves while it comes in. It starts invisible on the first frame rather than
 * shown and then hidden. In the editor it starts arrived, because a widget being
 * arranged must not vanish under the hand arranging it, and with reduced motion on
 * it starts arrived everywhere.
 */
val PlayedWidgetEntrance: WidgetEntrance = { entrance, order, delayMs, content ->
    WidgetArrival(entrance, order, delayMs, content)
}

@Composable
private fun WidgetArrival(entrance: Entrance, order: Int, delayMs: Int?, content: @Composable () -> Unit) {
    val editing = LocalEditMode.current is EditModeState.On
    val still = LocalCustomization.current.reduceMotion
    val arrives = entrance != Entrance.None && !editing && !still
    val role = when (entrance) {
        Entrance.Rise -> Motion.reveal
        Entrance.Settle -> Motion.emphasis
        else -> Motion.fade
    }
    // Read once: a widget's arrival is decided when it mounts, and an edit made
    // after that applies to the next opening rather than replaying this one.
    val progress = remember { Animatable(if (arrives) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!arrives) return@LaunchedEffect
        val wait = delayMs ?: autoEntranceDelayMs(order)
        if (wait > 0) delay(wait.coerceIn(0, MAX_DELAY_MS).milliseconds)
        progress.animateTo(1f, tween(role.durationMs, easing = role.easing))
    }
    Box(
        propagateMinConstraints = true,
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            // An overshooting curve carries the progress past one on the way in;
            // opacity cannot go past full, scale and travel can.
            alpha = p.coerceIn(0f, 1f)
            when (entrance) {
                Entrance.Rise -> translationY = (1f - p) * RISE_DISTANCE.toPx()
                Entrance.Settle -> {
                    val s = SETTLE_FROM + (1f - SETTLE_FROM) * p
                    scaleX = s
                    scaleY = s
                }
                else -> Unit
            }
        },
    ) {
        content()
    }
}

/** The delay a widget at [order] in its slot waits when it pins none of its own. */
internal fun autoEntranceDelayMs(order: Int): Int = order.coerceIn(0, STAGGER_STEPS) * STAGGER_MS

/**
 * The step between one widget and the next in a slot. A third of the fade it
 * staggers: long enough to read as an order, short enough that a column of five
 * is assembled before the eye has finished with the first.
 */
private const val STAGGER_MS = 70

/** Past this many places a slot stops staggering, so a long list does not trickle in. */
private const val STAGGER_STEPS = 6

/** The longest a pinned delay may hold a widget back. */
internal const val MAX_DELAY_MS = 2_000

/** How far a rising widget travels. A short way: it settles into place, it does not fly in. */
private val RISE_DISTANCE = 18.dp

/** The size a settling widget starts at, as a share of its own. */
private const val SETTLE_FROM = 0.94f
