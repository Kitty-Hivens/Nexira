package hivens.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.FlowSpec
import hivens.widget.model.SlotPath
import hivens.widget.model.traverse
import kotlin.math.roundToInt

// Tier 2 slot layout chrome. The slot's layout control left the layout flow:
// instead of an inline chip (which displaced the edited content), a slot is SELECTED
// (a zero-footprint modifier highlights it and reports its bounds) and its layout
// menu opens from a corner handle or a right-click -- as an overlay, never a child.

private val SLOT_HANDLE_SIZE = 26.dp

/**
 * A zero-footprint modifier applied to a slot's flow root. It highlights the slot
 * when selected (a snapshot-read draw -- no recomposition of the slot tree), reports
 * the selected slot's window bounds to the host (so the handle can anchor), and
 * routes a background primary press to selection / a secondary press to the context
 * menu at the cursor. A primary press already consumed by a child widget (its own
 * drag handle) is ignored, so this never steals widget interactions.
 */
internal fun slotChromeModifier(
    path: SlotPath,
    selectedSlot: State<SlotPath?>,
    onSelect: (SlotPath) -> Unit,
    onContextMenu: (SlotPath, Offset) -> Unit,
    onReportRect: (Rect) -> Unit,
): Modifier = Modifier.composed {
    val accent   = NxTheme.colors.primary
    val cornerPx = with(LocalDensity.current) { 12.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    val isSelected = selectedSlot.value == path
    // Push the rect to the host when this slot becomes (or moves while) selected --
    // onGloballyPositioned alone would miss a selection change with no layout change.
    LaunchedEffect(isSelected, bounds) { if (isSelected) onReportRect(bounds) }
    Modifier
        .onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            bounds = b
            originInWindow = b.topLeft
        }
        .drawWithContent {
            drawContent()
            if (isSelected) {
                val inset = strokePx / 2f
                drawRoundRect(
                    color        = accent,
                    topLeft      = Offset(inset, inset),
                    size         = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style        = Stroke(width = strokePx),
                )
            }
        }
        .pointerInput(path) {
            awaitPointerEventScope {
                while (true) {
                    // The slot is the LAST resort: handle the press on the Final pass, after
                    // the Main pass has let any widget under the cursor consume it. So a press
                    // on a widget reaches the widget (its own menu, drag or resize) and the
                    // slot acts only on a press no widget claimed -- explicit widget-over-slot
                    // priority, instead of racing the widget on the same (Main) pass.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.type != PointerEventType.Press) continue
                    val change = event.changes.first()
                    when {
                        event.buttons.isSecondaryPressed && !change.isConsumed -> {
                            onContextMenu(path, originInWindow + change.position)
                            change.consume()
                        }
                        event.buttons.isPrimaryPressed && !change.isConsumed -> {
                            onSelect(path)
                            change.consume()
                        }
                    }
                }
            }
        }
}

/**
 * The full-window overlay that carries the selected slot's affordances: a corner
 * handle anchored to the slot's top-right (opening a trigger-anchored menu) and a
 * cursor-anchored menu for right-click. Both share [SlotLayoutMenuContent]. Renders
 * nothing when no slot is selected.
 */
@Composable
internal fun SlotSelectionOverlay(
    selectedSlot: SlotPath?,
    selectedSlotRect: Rect?,
    handleMenuOpen: Boolean,
    cursorAnchor: Offset?,
    controller: EditModeController,
    onOpenHandleMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (selectedSlot == null) return
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.boundsInWindow().topLeft },
    ) {
        if (selectedSlotRect != null) {
            Box(
                modifier = Modifier.offset {
                    val x = (selectedSlotRect.right - overlayOrigin.x).roundToInt() - SLOT_HANDLE_SIZE.roundToPx() - 4.dp.roundToPx()
                    val y = (selectedSlotRect.top - overlayOrigin.y).roundToInt() + 4.dp.roundToPx()
                    IntOffset(x, y)
                },
            ) {
                SlotSelectionHandle(onClick = onOpenHandleMenu)
                NxContextMenu(expanded = handleMenuOpen, onDismissRequest = onDismiss) {
                    SlotLayoutMenuContent(selectedSlot, controller, onDismiss)
                }
            }
        }
        if (cursorAnchor != null) {
            NxContextMenu(anchorInWindow = cursorAnchor, expanded = true, onDismissRequest = onDismiss) {
                SlotLayoutMenuContent(selectedSlot, controller, onDismiss)
            }
        }
    }
}

/** The small corner handle that opens the selected slot's layout menu. */
@Composable
private fun SlotSelectionHandle(onClick: () -> Unit) {
    val s = LocalStrings.current
    NxSurface(
        level = NxSurfaceLevel.Floating,
        blurDp = 0f,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier         = Modifier.size(SLOT_HANDLE_SIZE).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(NxIcon.ViewQuilt, contentDescription = s.editorSlotLayoutHandle, tint = NxTheme.colors.primary, size = 16.dp)
        }
    }
}

/**
 * The layout menu body: a header, the four shapes the two parameters make (the
 * active one marked), and the one stepper, pointed at whichever number the current
 * mode has. Reads the slot's content live from [LocalLayoutGraph] so the mark and
 * the count track the model; the stepper nudges race-free via the controller and
 * leaves the menu open.
 */
@Composable
internal fun SlotLayoutMenuContent(
    path: SlotPath,
    controller: EditModeController,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val live = LocalLayoutGraph.current.traverse(path) ?: SlotContent()
    val flow = live.flow

    Text(
        text     = s.editorSlotLayoutMenuTitle,
        style    = MaterialTheme.typography.labelSmall,
        color    = NxTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 2.dp),
    )
    // Four presets over two parameters. The model has one flow with a direction
    // and a line length, and one placement mode with a unit, but a person
    // arranging a screen thinks in the four shapes those make, so the menu keeps
    // offering them by name.
    NxMenuItem(s.editorSlotStack, selected = flow != null && !flow.horizontal && flow.wrap == 0) {
        controller.setFlow(path, FlowSpec.Column); onClose()
    }
    NxMenuItem(s.editorSlotRow, selected = flow != null && flow.horizontal && flow.wrap == 0) {
        controller.setFlow(path, FlowSpec.Row); onClose()
    }
    NxMenuItem(s.editorSlotGrid, selected = flow != null && flow.wrap > 0) {
        controller.setFlow(path, FlowSpec.grid(DEFAULT_WRAP)); onClose()
    }
    NxMenuItem(s.editorSlotCanvas, selected = flow == null) {
        controller.setFlow(path, null); onClose()
    }

    // The one stepper, pointed at whichever number the current mode has. A flow
    // steps its line length, where 0 is "never wrap"; a placement slot steps the
    // lattice it measures in, where 0 is free placement. Shown for both and at
    // every value, because a stepper that disappears at zero is a door that locks
    // behind you: stepping a grid down to a row used to take the control away
    // with it, and the only way back was the menu item, which reset the number.
    if (flow != null) {
        SlotNumberRow(s.editorSlotGridColumns, flow.wrap, s, { controller.nudgeWrap(path, -1) }, { controller.nudgeWrap(path, 1) })
    } else {
        SlotNumberRow(s.editorSlotGridColumns, live.grid, s, { controller.nudgeGrid(path, -1) }, { controller.nudgeGrid(path, 1) })
    }
}

/** Default line length a slot takes when it becomes a wrapped flow. */
private const val DEFAULT_WRAP = 2

@Composable
private fun SlotNumberRow(
    label: String,
    value: Int,
    s: AppStrings,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = NxTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        NxIconButton(NxIcon.ChevronLeft, s.editorSlotGridColumnsDecrease, onClick = onDecrease, iconSize = 16.dp)
        Text(
            text     = "$value",
            style    = MaterialTheme.typography.bodyMedium,
            color    = NxTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        NxIconButton(NxIcon.ChevronRight, s.editorSlotGridColumnsIncrease, onClick = onIncrease, iconSize = 16.dp)
    }
}
