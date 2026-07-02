package hivens.ui.editor.decoration

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.widget.model.SlotPath

// Rendered by SlotRenderer when a slot has no widgets and the editor
// has supplied this decorator. Two purposes:
//   1. Visually communicate that an empty slot exists and accepts drops
//      ("drag a widget here" hint).
//   2. Register the slot's bounds with DropTargetRegistry so cross-slot
//      and palette drops can resolve the slot via slotForPoint.
//
// 80dp tall by default -- enough to be obviously droppable without
// stealing too much visual real estate. Subtle dashed primary border
// + breathing alpha animation so the placeholder reads as "active
// but not noisy".
@Composable
fun EmptySlotPlaceholder(
    path: SlotPath,
    registry: DropTargetRegistry,
) {
    val s = LocalStrings.current
    val breath by rememberInfiniteTransition(label = "empty-slot-breath").animateFloat(
        initialValue  = 0.45f,
        targetValue   = 0.85f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "empty-slot-breath-value",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(vertical = 6.dp)
            .onGloballyPositioned { c: LayoutCoordinates ->
                registry.registerSlot(path, c.boundsInWindow())
            },
        contentAlignment = Alignment.Center,
    ) {
        // Dashed border via Canvas so we can use PathEffect; M3's
        // border modifier does not support dashed strokes.
        val borderColor = NxTheme.colors.primary.copy(alpha = breath)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color  = borderColor,
                style  = Stroke(
                    width      = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            )
        }
        Box(
            modifier         = Modifier.padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(icon = NxIcon.Add,
                    contentDescription = null,
                    tint               = NxTheme.colors.primary.copy(alpha = breath),
                    modifier           = Modifier.padding(end = 6.dp),
                )
                Text(
                    text       = s.editorDragWidgetHere,
                    style      = MaterialTheme.typography.bodySmall,
                    color      = NxTheme.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
