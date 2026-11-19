package hivens.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.theme.Motion
import hivens.widget.api.WidgetEntrance
import hivens.widget.model.Entrance
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * The arrival the kernel asks for, drawn as the look answers it.
 *
 * What each character means, how long it runs, on which curve, how far a rise
 * travels and how much a settle grows, is the look's answer for it
 * ([Motion.arrival]). Nothing here picks a duration.
 *
 * Widgets in one slot come up one after another, by their place in it, so a
 * surface assembles rather than appears ([Motion.stagger]). A delay the node pins
 * replaces that place.
 *
 * A character or a delay the node itself names is the node's, and plays as set
 * whatever the look holds still: a widget is stronger than the look. One its widget
 * declares, and the slot's stagger, are the look's to answer, and where the look
 * holds decoration still the widget is in place from the first frame.
 *
 * Draw-phase only: the widget is laid out at its final size and position from the
 * first frame and the arrival is alpha, offset and scale over it, so nothing around
 * it moves while it comes in. It starts invisible on the first frame rather than
 * shown and then hidden. In the editor it starts arrived, because a widget being
 * arranged must not vanish under the hand arranging it.
 */
val PlayedWidgetEntrance: WidgetEntrance = { entrance, onNode, order, delayMs, content ->
    WidgetArrival(entrance, onNode, order, delayMs, content)
}

@Composable
private fun WidgetArrival(entrance: Entrance, onNode: Boolean, order: Int, delayMs: Int?, content: @Composable () -> Unit) {
    val editing = LocalEditMode.current is EditModeState.On
    val arrival = Motion.arrival(entrance)
    val role = when {
        arrival == null || editing -> null
        onNode -> arrival.role
        else -> Motion.decoration(arrival.role)
    }
    val stagger = Motion.stagger(order)
    // Read once: a widget's arrival is decided when it mounts, and an edit made
    // after that applies to the next opening rather than replaying this one.
    val progress = remember { Animatable(if (role != null) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (role == null) return@LaunchedEffect
        val wait = delayMs?.coerceIn(0, MAX_DELAY_MS) ?: stagger
        if (wait > 0) delay(wait.milliseconds)
        progress.animateTo(1f, role.of())
    }
    val travel = arrival?.travel ?: 0.dp
    val fromScale = arrival?.fromScale ?: 1f
    Box(
        propagateMinConstraints = true,
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            // An overshooting curve carries the progress past one on the way in;
            // opacity cannot go past full, scale and travel can.
            alpha = p.coerceIn(0f, 1f)
            if (travel != 0.dp) translationY = (1f - p) * travel.toPx()
            if (fromScale != 1f) {
                val s = fromScale + (1f - fromScale) * p
                scaleX = s
                scaleY = s
            }
        },
    ) {
        content()
    }
}

/** The longest a pinned delay may hold a widget back. */
internal const val MAX_DELAY_MS = 2_000
