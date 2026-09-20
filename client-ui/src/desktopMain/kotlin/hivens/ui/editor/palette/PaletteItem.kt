package hivens.ui.editor.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.dragSource
import hivens.ui.editor.windowPointToSlotDp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalMonoFamily
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.FlowSpec
import hivens.widget.model.seedPlacement
import hivens.widget.model.WidgetSizing
import hivens.widget.model.traverse

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
    // The drop handler lives inside a gesture the row starts once and never
    // restarts -- its pointerInput is keyed on the payload, which is the same
    // value for the row's whole life -- so a plain read here would freeze the
    // graph at the first drag from this row. Every drop after that resolved the
    // target slot's mode and widget count from a layout that had since
    // moved on: three widgets from one row into a canvas all seeded from the same
    // count and landed on top of each other.
    val graph by rememberUpdatedState(LocalLayoutGraph.current)
    val s = LocalStrings.current
    // Resolve the widget's label via key-indirection (see AppStrings.widgetLabel).
    val label = s.widgetLabel(descriptor.displayName)
    val density = LocalDensity.current.density

    val background = if (isHovered) NxTheme.colors.primary.copy(alpha = 0.12f)
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
                ghost                 = { PaletteGhost(displayName = label, sizing = descriptor.sizing) },
                onDragEnd             = { pointer ->
                    val targetPath = registry.slotForPoint(pointer) ?: return@dragSource
                    val target = graph.traverse(targetPath)
                    if (target != null && target.flow == null) {
                        // A placement slot needs the widget to arrive somewhere, and
                        // where depends on what it measures in. A free slot takes the
                        // release point, converted from the window through the slot's
                        // reported origin. A lattice takes the first free cell instead:
                        // the pointer names a dp, and turning that into a cell needs
                        // geometry this row does not have and the model already knows.
                        val seed = seedPlacement(target.widgets.size, target.grid, target.widgets)
                        val slotRect = registry.slotRect(targetPath)
                        val placement = if (target.grid == 0 && slotRect != null) {
                            val (xDp, yDp) = windowPointToSlotDp(
                                pointer.x, pointer.y, slotRect.left, slotRect.top, density,
                            )
                            // Released near the far edge, a widget born at the raw
                            // point starts there and hangs out of the slot, with only
                            // the renderer's own grab margin keeping any of it
                            // reachable. The floor at zero was the only bound there
                            // was. A ceiling as well, so a drop anywhere inside the
                            // slot puts the whole widget inside the slot.
                            seed.copy(
                                x = xDp.coerceIn(0f, ((slotRect.width / density) - DROP_INSET_DP).coerceAtLeast(0f)),
                                y = yDp.coerceIn(0f, ((slotRect.height / density) - DROP_INSET_DP).coerceAtLeast(0f)),
                                // Arrives at the size it says it wants, which is
                                // also the size the ghost just showed. A claim of
                                // zero drew the same pixels but told the resize
                                // handle nothing, so the first drag jumped from a
                                // number nobody had written to one the pointer did.
                                width = descriptor.sizing.prefWidth.toFloat(),
                                height = descriptor.sizing.prefHeight.toFloat(),
                            )
                        } else {
                            seed
                        }
                        editController.addWidget(
                            targetPath, descriptor.kind, descriptor.slots,
                            index     = target.widgets.size,
                            placement = placement,
                            surface   = descriptor.defaultSurface,
                        )
                    } else {
                        val flow = target?.flow ?: FlowSpec.Column
                        val index = registry.insertionIndexInSlot(targetPath, pointer, flow)
                        editController.addWidget(targetPath, descriptor.kind, descriptor.slots, index, surface = descriptor.defaultSurface)
                    }
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
                .background(NxTheme.colors.primary.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = label.firstOrNull()?.uppercase() ?: "?",
                style      = MaterialTheme.typography.titleMedium,
                color      = NxTheme.colors.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text       = label,
                style      = MaterialTheme.typography.bodyMedium,
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text       = descriptor.kind.value,
                style      = MaterialTheme.typography.labelSmall,
                color      = NxTheme.colors.textSecondary,
                fontFamily = LocalMonoFamily.current,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
        Symbol(icon = NxIcon.DragIndicator,
            contentDescription = null,
            tint               = NxTheme.colors.textSecondary.copy(alpha = if (isHovered) 0.9f else 0.45f),
            modifier           = Modifier.size(18.dp),
        )
    }
}

/**
 * What follows the pointer out of the palette.
 *
 * A label chip rather than the real widget: a @Composable exception cannot be
 * caught mid-composition, so rendering a surface-context-dependent widget during
 * a palette drag crashes every frame.
 *
 * Inside the footprint the widget declared, when it declares one. The chip alone
 * said what was being added and nothing about how much room it takes, so the only
 * way to find out was to drop it and look. A widget that declares no preferred
 * size still gets the bare chip, because an invented rectangle would be worse
 * than none.
 */
@Composable
private fun PaletteGhost(displayName: String, sizing: WidgetSizing) {
    val w = sizing.prefWidth
    val h = sizing.prefHeight
    if (w <= 0 || h <= 0) {
        PaletteGhostChip(displayName)
        return
    }
    Box(
        modifier = Modifier
            .size(w.dp, h.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NxTheme.colors.primary.copy(alpha = 0.16f))
            .border(2.dp, NxTheme.colors.primary.copy(alpha = 0.8f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        PaletteGhostChip(displayName)
    }
}

@Composable
private fun PaletteGhostChip(displayName: String) {
    Surface(
        color           = NxTheme.colors.primary,
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

/**
 * How far in from the far edge of a slot a palette drop can land.
 *
 * Not the widget's own size, which nothing knows before it is mounted, so this
 * is the same floor a placed widget is held to: enough of it is inside that it
 * can be seen and grabbed, and moving it the rest of the way is a drag.
 */
private const val DROP_INSET_DP = 48f
