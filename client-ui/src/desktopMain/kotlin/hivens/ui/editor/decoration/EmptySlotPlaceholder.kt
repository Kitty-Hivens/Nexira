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
import androidx.compose.ui.geometry.CornerRadius
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
import hivens.ui.theme.Motion
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
    val breathRhythm = Motion.ownRhythm(BREATH_MS)
    val breath by rememberInfiniteTransition(label = "empty-slot-breath").animateFloat(
        initialValue  = 0.45f,
        targetValue   = 0.85f,
        animationSpec = infiniteRepeatable(
            animation  = breathRhythm.of(),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "empty-slot-breath-value",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            // Measured before the inset, not after. Reporting the padded box
            // registered a drop target six dp lower and twelve shorter than the
            // thing on screen, so a drop aimed at the top edge of the dashes
            // missed and was discarded without a word.
            .onGloballyPositioned { c: LayoutCoordinates ->
                registry.registerSlot(path, c.boundsInWindow())
            }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Dashed border via Canvas so we can use PathEffect; M3's
        // border modifier does not support dashed strokes.
        val borderColor = NxTheme.colors.primary.copy(alpha = breath)
        // Every length here is dp converted at draw time. They used to be bare
        // floats, which a DrawScope reads as device pixels: on a 2K display the
        // border came out at half its weight with half-length dashes, and the
        // corner ignored the style entirely.
        val corner = 12.dp
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color  = borderColor,
                style  = Stroke(
                    width      = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f),
                ),
                cornerRadius = CornerRadius(corner.toPx(), corner.toPx()),
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

/** How long an empty slot takes to breathe in and out. */
private const val BREATH_MS = 1600
