package hivens.ui.editor.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.dragSource
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.WidgetDescriptor

// Palette row. Click + drag from the row drops the widget into the
// hit-tested slot under the cursor. The ghost is a labeled chip rather
// than the real widget render -- @Composable exceptions can't be
// caught by runCatching mid-composition, so trying to render
// surface-context-dependent widgets during a palette drag would crash
// every frame. The label ghost is robust and still communicates the
// drop target intent ("Adding: Clock") clearly while the cursor moves.
@Composable
fun PaletteItem(
    descriptor: WidgetDescriptor,
    controller: DragController,
    registry: DropTargetRegistry,
    editController: EditModeController,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var rowBounds by remember { mutableStateOf<Rect?>(null) }

    val background = if (isHovered) CelestiaTheme.colors.primary.copy(alpha = 0.12f)
                     else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .onGloballyPositioned { c: LayoutCoordinates -> rowBounds = c.boundsInWindow() }
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .dragSource(
                controller            = controller,
                payload               = DragPayload.PaletteWidget(descriptor.kind),
                widgetBoundsProvider  = { rowBounds },
                ghost                 = { PaletteGhost(displayName = descriptor.displayName) },
                onDragEnd             = { pointer ->
                    val targetPath = registry.slotForPoint(pointer) ?: return@dragSource
                    val index = registry.insertionIndexInSlot(targetPath, pointer)
                    editController.addWidget(targetPath, descriptor.kind, index)
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Tiny icon block -- first letter of displayName as a kind of
        // visual anchor. When Phase 5 widget-supplied previews land,
        // this slot becomes the real widget thumbnail.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CelestiaTheme.colors.primary.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = descriptor.displayName.firstOrNull()?.uppercase() ?: "?",
                style      = MaterialTheme.typography.titleMedium,
                color      = CelestiaTheme.colors.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text       = descriptor.displayName,
                style      = MaterialTheme.typography.bodyMedium,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text       = descriptor.kind.value,
                style      = MaterialTheme.typography.labelSmall,
                color      = CelestiaTheme.colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector        = Icons.Default.DragIndicator,
            contentDescription = null,
            tint               = CelestiaTheme.colors.textSecondary.copy(alpha = if (isHovered) 0.9f else 0.45f),
            modifier           = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PaletteGhost(displayName: String) {
    Surface(
        color           = CelestiaTheme.colors.primary,
        contentColor    = Color.White,
        shape           = RoundedCornerShape(10.dp),
        shadowElevation = 10.dp,
        modifier        = Modifier.shadow(elevation = 12.dp, shape = RoundedCornerShape(10.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = displayName.firstOrNull()?.uppercase() ?: "?",
                    style      = MaterialTheme.typography.labelLarge,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text       = displayName,
                style      = MaterialTheme.typography.bodyMedium,
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text  = "→ drop",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}
