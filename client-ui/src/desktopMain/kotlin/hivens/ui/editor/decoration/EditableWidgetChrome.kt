package hivens.ui.editor.decoration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.dragSource
import hivens.ui.editor.dnd.widgetBounds
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.SlotAddress
import hivens.widget.model.WidgetInstance

// Wraps a single widget with edit-mode chrome: drag handle (always
// visible, opacity boosts on hover), remove button (hover-only, hidden
// when descriptor says non-removable), faint border outline.
//
// The whole wrapper is also a drop-target bounds-reporter for its own
// rect -- the registry uses this to compute insertion-index hit-tests
// during a drag.
@Composable
fun EditableWidgetChrome(
    address: SlotAddress,
    index: Int,
    descriptor: WidgetDescriptor,
    instance: WidgetInstance,
    controller: DragController,
    registry: DropTargetRegistry,
    onRemove: () -> Unit,
    onCommitDrop: (committedPointer: androidx.compose.ui.geometry.Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var widgetWindowBounds by remember { mutableStateOf<Rect?>(null) }
    val activeDrag = controller.active
    val isThisDragging = (activeDrag?.payload as? DragPayload.ExistingWidget)
        ?.instance?.instanceId == instance.instanceId

    // The ghost lambda is invoked by DragGhostOverlay at the host
    // level -- outside the surface composable's CompositionLocalProvider
    // chain. Widgets like HomeNewRecent read surface-scoped locals
    // (LocalHomeNewContext, LocalLibraryContext, ...) and would throw
    // when the ghost recomposes them. Snapshot the locals here and
    // restore them inside the ghost so the widget renders identically
    // wherever it lands.
    val capturedLocals = currentCompositionLocalContext

    // Source widget fades to 30% while being dragged -- the ghost is
    // doing the work on top. Once drag ends, we ramp back smoothly.
    val sourceAlpha by animateFloatAsState(
        targetValue   = if (isThisDragging) 0.30f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "edit-source-alpha",
    )
    val borderAlpha by animateFloatAsState(
        targetValue   = if (isHovered) 0.55f else 0.18f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "edit-border-alpha",
    )

    Box(
        modifier = Modifier
            .hoverable(interaction)
            .onGloballyPositioned { coords: LayoutCoordinates ->
                val rect = coords.boundsInWindow()
                widgetWindowBounds = rect
                registry.registerWidget(address, instance.instanceId, index, rect)
            }
            .widgetBounds(registry, address, instance.instanceId, index)
            .padding(2.dp)
            .border(
                width = 1.dp,
                color = CelestiaTheme.colors.primary.copy(alpha = borderAlpha),
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Box(Modifier.alpha(sourceAlpha)) { content() }

        // Drag handle: always visible (so the user discovers it),
        // brightens on hover. Attached pointerInput drives the drag.
        Surface(
            color    = CelestiaTheme.colors.surface.copy(alpha = if (isHovered) 0.95f else 0.65f),
            shape    = RoundedCornerShape(6.dp),
            shadowElevation = 0.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .size(22.dp)
                .dragSource(
                    controller            = controller,
                    payload               = DragPayload.ExistingWidget(address, index, instance),
                    widgetBoundsProvider  = { widgetWindowBounds },
                    ghost                 = {
                        CompositionLocalProvider(capturedLocals) { content() }
                    },
                    onDragEnd             = onCommitDrop,
                ),
        ) {
            Icon(
                imageVector        = Icons.Default.DragIndicator,
                contentDescription = "Drag to reorder",
                tint               = CelestiaTheme.colors.textSecondary,
                modifier           = Modifier.size(16.dp).padding(0.dp),
            )
        }

        // Remove button: hover-only, hidden on non-removable widgets.
        // Animated fade so it does not pop in jarringly.
        AnimatedVisibility(
            visible  = isHovered && descriptor.removable,
            enter    = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
            exit     = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        ) {
            Surface(
                color    = CelestiaTheme.colors.error.copy(alpha = 0.85f),
                shape    = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .size(22.dp)
                    .pointerInput(instance.instanceId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Release) {
                                    onRemove()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            ) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Remove widget",
                    tint               = Color.White,
                    modifier           = Modifier.size(14.dp).padding(0.dp).graphicsLayer { },
                )
            }
        }
    }
}
