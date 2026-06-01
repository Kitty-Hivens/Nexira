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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse

// Wraps a single widget with edit-mode chrome: drag handle (always
// visible, opacity boosts on hover), remove button (hover-only, hidden
// when descriptor says non-removable), prop "tune" gear (hover, only when
// the widget has props), faint border outline, and a drop indicator.
//
// Phase G: the chrome follows the slot's orientation. In a Column slot it
// wraps in a Column with horizontal drop bars above/below; in a Row slot
// it wraps in a Row with vertical drop bars left/right. The hover buttons
// live in a Box-scoped inner section so they use plain BoxScope `.align`
// regardless of the outer Row/Column.
//
// The whole wrapper is also a drop-target bounds-reporter for its own
// rect -- the registry uses this to compute insertion-index hit-tests
// during a drag.
@Composable
fun EditableWidgetChrome(
    path: SlotPath,
    index: Int,
    descriptor: WidgetDescriptor,
    instance: WidgetInstance,
    controller: DragController,
    registry: DropTargetRegistry,
    orientation: SlotOrientation,
    onRemove: () -> Unit,
    onEditProps: () -> Unit,
    onCommitDrop: (committedPointer: androidx.compose.ui.geometry.Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var widgetWindowBounds by remember { mutableStateOf<Rect?>(null) }
    var forceRemoveOpen by remember { mutableStateOf(false) }
    val activeDrag = controller.active
    val isThisDragging = (activeDrag?.payload as? DragPayload.ExistingWidget)
        ?.instance?.instanceId == instance.instanceId

    val isRow = orientation == SlotOrientation.Row

    // Drop-indicator hit test. Reading controller.active recomposes on
    // every pointer update; traverse + registry queries are O(depth +
    // widgets-in-slot) and cheap enough to do per-frame for the few
    // dozen widgets a surface can hold.
    val graph = LocalLayoutGraph.current
    val slotCount = graph.traverse(path)?.widgets?.size ?: 0
    val isLastInSlot = index == slotCount - 1
    val dropTargetPath = activeDrag?.let { registry.slotForPoint(it.pointerInWindow) }
    val dropInsertionIdx = if (activeDrag != null && dropTargetPath == path) {
        registry.insertionIndexInSlot(path, activeDrag.pointerInWindow, orientation)
    } else -1
    val showIndicatorBefore = dropInsertionIdx == index
    val showIndicatorAfter  = isLastInSlot && dropInsertionIdx == slotCount

    // Nesting depth -> subtle border alpha boost. Depth 0 (root surface
    // slot) keeps the original 0.18/0.55 alpha; each level adds 0.06
    // and we clip at 0.40/0.85 so deep stacks stay readable.
    val depthBoost = (path.nested.size * 0.06f).coerceAtMost(0.22f)

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
        targetValue   = if (isHovered) 0.55f + depthBoost else 0.18f + depthBoost,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "edit-border-alpha",
    )

    // Bordered widget + hover handles. Box-scoped so the AnimatedVisibility
    // buttons use plain BoxScope `.align` -- no this@Column / this@Row
    // qualifier, which lets the outer wrapper be either orientation.
    val widgetBox: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .then(if (isRow) Modifier.padding(horizontal = 4.dp) else Modifier.padding(vertical = 4.dp))
                .hoverable(interaction)
                .onGloballyPositioned { coords: LayoutCoordinates ->
                    val rect = coords.boundsInWindow()
                    widgetWindowBounds = rect
                    registry.registerWidget(path, instance.instanceId, index, rect)
                }
                .widgetBounds(registry, path, instance.instanceId, index)
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
                        payload               = DragPayload.ExistingWidget(path, index, instance),
                        widgetBoundsProvider  = { widgetWindowBounds },
                        ghost                 = {
                            CompositionLocalProvider(capturedLocals) { content() }
                        },
                        onDragEnd             = onCommitDrop,
                    ),
            ) {
                Icon(
                    imageVector        = Icons.Default.DragIndicator,
                    contentDescription = s.editorDragReorder,
                    tint               = CelestiaTheme.colors.textSecondary,
                    modifier           = Modifier.size(16.dp).padding(0.dp),
                )
            }

            // Remove button: hover-only, red close icon. Hidden on
            // non-removable widgets (those get the force-remove
            // affordance below instead).
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
                        contentDescription = s.editorDelete,
                        tint               = CelestiaTheme.colors.onPrimary,
                        modifier           = Modifier.size(14.dp).padding(0.dp).graphicsLayer { },
                    )
                }
            }

            // Prop editor affordance: hover-only "tune" gear, shown only
            // when the widget declares props (propsSerializer != null).
            // Sits left of the remove/force-remove button at the top-end.
            // Same Release-consume pattern as remove so the tap does not
            // start a drag.
            AnimatedVisibility(
                visible  = isHovered && descriptor.propsSerializer != null,
                enter    = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit     = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 30.dp),
            ) {
                Surface(
                    color    = CelestiaTheme.colors.primary.copy(alpha = 0.85f),
                    shape    = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .size(22.dp)
                        .pointerInput(instance.instanceId) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Release) {
                                        onEditProps()
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                    Icon(
                        imageVector        = Icons.Default.Tune,
                        contentDescription = s.editorConfigure,
                        tint               = CelestiaTheme.colors.onPrimary,
                        modifier           = Modifier.size(14.dp).padding(0.dp),
                    )
                }
            }

            // Force-remove affordance for non-removable widgets. Same
            // top-right slot as the regular remove button, but uses a
            // warning-tinted DeleteForever icon and gates the action
            // behind a confirmation dialog. Without this, a non-
            // removable widget that ends up in the wrong slot (preset
            // import, plugin install, or a user-driven move out of its
            // home surface) has no exit path short of editing
            // layout-graph.json by hand or invoking the per-surface
            // reset on the edit pill.
            AnimatedVisibility(
                visible  = isHovered && !descriptor.removable,
                enter    = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit     = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Surface(
                    color    = CelestiaTheme.colors.warnAccent.copy(alpha = 0.85f),
                    shape    = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .size(22.dp)
                        .pointerInput(instance.instanceId) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Release) {
                                        forceRemoveOpen = true
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                    Icon(
                        imageVector        = Icons.Default.DeleteForever,
                        contentDescription = s.editorForceRemove,
                        tint               = CelestiaTheme.colors.onPrimary,
                        modifier           = Modifier.size(14.dp).padding(0.dp),
                    )
                }
            }
        }
    }

    if (isRow) {
        Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.Top) {
            if (showIndicatorBefore) DropIndicator(isRow = true)
            widgetBox()
            if (showIndicatorAfter) DropIndicator(isRow = true)
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showIndicatorBefore) DropIndicator(isRow = false)
            widgetBox()
            if (showIndicatorAfter) DropIndicator(isRow = false)
        }
    }

    if (forceRemoveOpen) {
        AlertDialog(
            onDismissRequest = { forceRemoveOpen = false },
            title            = { Text(s.editorForceRemoveTitle) },
            text             = {
                Text(
                    text = s.editorForceRemoveBody(descriptor.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forceRemoveOpen = false
                    onRemove()
                }) { Text(s.editorDelete, color = CelestiaTheme.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { forceRemoveOpen = false }) { Text(s.editorCancel) }
            },
        )
    }
}

// Drop insertion bar. Horizontal (full width, 2dp tall) for a Column
// slot; vertical (full height, 2dp wide) for a Row slot.
@Composable
private fun DropIndicator(isRow: Boolean) {
    if (isRow) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .padding(vertical = 4.dp)
                .background(CelestiaTheme.colors.primary),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .padding(horizontal = 4.dp)
                .background(CelestiaTheme.colors.primary),
        )
    }
}
