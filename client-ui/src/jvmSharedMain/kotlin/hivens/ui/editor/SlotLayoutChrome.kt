package hivens.ui.editor

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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import hivens.ui.editor.dnd.DropTargetRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.i18n.api.Strings
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.press.clickable
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.widget.api.LocalDocument
import hivens.widget.model.SlotContent
import hivens.widget.model.FlowSpec
import hivens.widget.model.NodePath
import hivens.widget.model.ViewportMode
import hivens.widget.model.ViewportSpec
import hivens.widget.model.contentAt
import hivens.widget.model.holdsOwn
import hivens.widget.model.isSingleSlot
import hivens.widget.model.viewportMode
import kotlin.math.roundToInt
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

// Tier 2 slot layout chrome. The slot's layout control left the layout flow:
// instead of an inline chip (which displaced the edited content), a slot is SELECTED
// (a zero-footprint modifier highlights it and reports its bounds) and its layout
// menu opens from a corner handle or a right-click -- as an overlay, never a child.

private val SLOT_HANDLE_SIZE = 26.dp

/**
 * A zero-footprint modifier applied to every slot's root. It tells [registry] where the slot is on screen, which is
 * what a drop is aimed by, highlights the slot when selected, reports the selected slot's window bounds to the host
 * (so the handle can anchor), and routes a background primary press to selection and a secondary press to the
 * context menu at the cursor. A press a widget already took is ignored, so this never steals widget interactions,
 * and of nested slots only the innermost under the pointer acts.
 *
 * Whether it is selected is read where it is drawn and where its rect is reported, never while composing, so a change
 * of selection redraws two slots and recomposes none.
 */
internal fun slotChromeModifier(
    path: NodePath,
    /** Whether the slot holds nothing, which is drawn as an outline round the room it has. */
    empty: Boolean,
    registry: DropTargetRegistry,
    selectedSlot: State<NodePath?>,
    onSelect: (NodePath) -> Unit,
    onContextMenu: (NodePath, Offset) -> Unit,
    onReportRect: (Rect) -> Unit,
): Modifier = Modifier.composed {
    val accent   = NxColor.lead()
    val cornerPx = with(LocalDensity.current) { 12.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(path, registry) { onDispose { registry.withdrawSlot(path) } }
    // Push the rect to the host when this slot becomes (or moves while) selected --
    // onGloballyPositioned alone would miss a selection change with no layout change.
    LaunchedEffect(path) {
        snapshotFlow { (selectedSlot.value == path) to bounds }.collect { (selected, rect) -> if (selected) onReportRect(rect) }
    }
    Modifier
        .onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            bounds = b
            originInWindow = coords.positionInWindow()
            registry.registerSlot(path, b)
        }
        .drawWithContent {
            drawContent()
            if (empty) {
                val inset = strokePx / 2f
                drawRoundRect(
                    color        = accent.copy(alpha = 0.45f),
                    topLeft      = Offset(inset, inset),
                    size         = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style        = Stroke(width = strokePx / 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            if (selectedSlot.value == path) {
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
                    // The Final pass runs outer slots first, so a press in a nested slot is the nested one's.
                    if (registry.slotForPoint(originInWindow + change.position) != path) continue
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
        .then(ShareWithLayersBeneath)
}

/**
 * Lets a press go on to what is drawn beneath as well. A slot drawn over another, the centre's floating lane over the
 * page, covers the whole of it while what stands in it may draw nothing at rest, and a container's content covers the
 * container's own press layer. Compose hands a press to the uppermost of overlapping siblings only, so the lane took
 * presses meant for the page and a rail's content took the presses meant for the rail. Shared, every chrome under the
 * pointer sees the press and the one the registry names takes it.
 */
internal data object ShareWithLayersBeneath : ModifierNodeElement<ShareWithLayersBeneathNode>() {
    override fun create() = ShareWithLayersBeneathNode()
    override fun update(node: ShareWithLayersBeneathNode) = Unit
}

internal class ShareWithLayersBeneathNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
    override fun sharePointerInputWithSiblings(): Boolean = true
}

/**
 * The full-window overlay that carries the selected slot's affordances: a corner
 * handle anchored to the slot's top-right (opening a trigger-anchored menu) and a
 * cursor-anchored menu for right-click. Both share [SlotLayoutMenuContent]. Renders
 * nothing when no slot is selected.
 */
@Composable
internal fun SlotSelectionOverlay(
    selectedSlot: NodePath?,
    selectedSlotRect: Rect?,
    handleMenuOpen: Boolean,
    cursorAnchor: Offset?,
    editor: DocumentEditor,
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
                    SlotLayoutMenuContent(selectedSlot, editor, onDismiss)
                }
            }
        }
        if (cursorAnchor != null) {
            NxContextMenu(anchorInWindow = cursorAnchor, expanded = true, onDismissRequest = onDismiss) {
                SlotLayoutMenuContent(selectedSlot, editor, onDismiss)
            }
        }
    }
}

/** The small corner handle that opens the selected slot's layout menu. */
@Composable
private fun SlotSelectionHandle(onClick: () -> Unit) {
    val s = nexiraStrings()
    NxSurface(
        kind = SurfaceKind.Popup,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier         = Modifier.size(SLOT_HANDLE_SIZE).layerTrigger().clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(NxIcon.ViewQuilt, contentDescription = s.text("editorSlotLayoutHandle"), tint = NxColor.lead(), size = 16.dp)
        }
    }
}

/**
 * The layout menu body: a header, the four shapes the two parameters make (the
 * active one marked), and the one stepper, pointed at whichever number the current
 * mode has. Reads the slot's content live from [LocalDocument] so the mark and
 * the count track the model. The stepper nudges through the editor, which applies
 * one edit after another, and leaves the menu open.
 */
@Composable
internal fun SlotLayoutMenuContent(
    path: NodePath,
    editor: DocumentEditor,
    onClose: () -> Unit,
) {
    val s = nexiraStrings()
    val document = LocalDocument.current
    val live = document?.contentAt(path) ?: SlotContent()
    val flow = live.flow

    // A slot of a frame widget is shared by every config of the composition unless the one drawn holds it as its own,
    // and which one it is decides what every change below reaches, so it is said first and can be changed right here.
    // Offered only where there is another config to differ from, or the slot is held already and can be given back.
    val configs = document?.compositions?.get(path.composition)?.configs?.size ?: 0
    val own = document?.holdsOwn(path) == true
    if (document != null && document.isSingleSlot(path) && (own || configs > 1)) {
        MenuSectionTitle(s.text(if (own) "editorSlotOwn" else "editorSlotShared"))
        NxMenuItem(s.text(if (own) "editorSlotShareOwn" else "editorSlotHoldOwn")) {
            if (own) editor.shareOwn(path) else editor.holdOwn(path)
            onClose()
        }
    }
    Text(
        text     = s.text("editorSlotLayoutMenuTitle"),
        style    = MaterialTheme.typography.labelSmall,
        color    = NxInk.quiet,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 2.dp),
    )
    // Four presets over two parameters. The model has one flow with a direction
    // and a line length, and one placement mode with a unit, but a person
    // arranging a screen thinks in the four shapes those make, so the menu keeps
    // offering them by name.
    NxMenuItem(s.text("editorSlotStack"), selected = flow != null && !flow.horizontal && flow.wrap == 0) {
        editor.flow(path, FlowSpec.Column); onClose()
    }
    NxMenuItem(s.text("editorSlotRow"), selected = flow != null && flow.horizontal && flow.wrap == 0) {
        editor.flow(path, FlowSpec.Row); onClose()
    }
    NxMenuItem(s.text("editorSlotGrid"), selected = flow != null && flow.wrap > 0) {
        editor.flow(path, FlowSpec.grid(DEFAULT_WRAP)); onClose()
    }
    // Canvas is always adaptive now: it scales its arrangement to fit rather than
    // clipping, so there is no per-slot mode to offer here.
    NxMenuItem(s.text("editorSlotCanvas"), selected = flow == null) {
        editor.flow(path, null); onClose()
    }

    // The one stepper, pointed at whichever number the current mode has. A flow
    // steps its line length, where 0 is "never wrap"; a placement slot steps the
    // lattice it measures in, where 0 is free placement. Shown for both and at
    // every value, because a stepper that disappears at zero is a door that locks
    // behind you: stepping a grid down to a row used to take the control away
    // with it, and the only way back was the menu item, which reset the number.
    if (flow != null) {
        SlotNumberRow(s.text("editorSlotGridColumns"), flow.wrap, s, { editor.nudgeWrap(path, -1) }, { editor.nudgeWrap(path, 1) })
    } else if (live.viewportMode != ViewportMode.Map) {
        // Not on a map, which has no width for a lattice to divide.
        SlotNumberRow(s.text("editorSlotGridColumns"), live.grid, s, { editor.nudgeGrid(path, -1) }, { editor.nudgeGrid(path, 1) })
    }

    // How the slot shows what does not fit, which is a separate question from how
    // it arranges what it holds: any of the four shapes above can scroll or not.
    // Static is first and is what every slot is until somebody says otherwise.
    val mode = live.viewportMode
    val bar = live.viewport?.scrollbar ?: true
    val paged = (mode as? ViewportMode.Scroll)?.paged == true
    // Keeps the page-by-page setting across a change of axis: it is a property of
    // how the slot scrolls, not of which way.
    val scrollKind = if (paged) ViewportSpec.PAGES else ViewportSpec.SCROLL
    MenuSectionTitle(s.text("editorSlotViewportTitle"))
    NxMenuItem(s.text("editorViewportStatic"), selected = mode == ViewportMode.Static) {
        editor.viewport(path, null); onClose()
    }
    NxMenuItem(s.text("editorViewportDown"), selected = mode is ViewportMode.Scroll && !mode.horizontal) {
        editor.viewport(path, ViewportSpec(scrollKind, ViewportSpec.VERTICAL, bar)); onClose()
    }
    NxMenuItem(s.text("editorViewportRight"), selected = mode is ViewportMode.Scroll && mode.horizontal) {
        editor.viewport(path, ViewportSpec(scrollKind, ViewportSpec.HORIZONTAL, bar)); onClose()
    }
    // A plane with no edges. It holds placed widgets, so a flow put on one becomes
    // a canvas in the same step.
    NxMenuItem(s.text("editorViewportMap"), selected = mode == ViewportMode.Map) {
        editor.viewport(path, ViewportSpec.Map); onClose()
    }
    // Stays open: it is a setting of the scroll just picked, and a reader flipping
    // it wants to see the bar come and go without reopening the menu.
    val current = live.viewport
    if (mode is ViewportMode.Scroll && current != null) {
        // A whole screen at a time: the wheel turns a page, a scroll settles on one.
        NxMenuItem(s.text("editorViewportPaged"), selected = paged) {
            editor.viewport(path, current.copy(kind = if (paged) ViewportSpec.SCROLL else ViewportSpec.PAGES))
        }
        NxMenuItem(s.text("editorViewportScrollbar"), selected = bar) {
            editor.viewport(path, current.copy(scrollbar = !bar))
        }
    }
}

@Composable
private fun MenuSectionTitle(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = NxInk.quiet,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Default line length a slot takes when it becomes a wrapped flow. */
private const val DEFAULT_WRAP = 2

@Composable
private fun SlotNumberRow(
    label: String,
    value: Int,
    s: Strings,
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
            color    = NxInk.main,
            modifier = Modifier.weight(1f),
        )
        NxIconButton(NxIcon.ChevronLeft, s.text("editorSlotGridColumnsDecrease"), onClick = onDecrease, iconSize = 16.dp)
        Text(
            text     = "$value",
            style    = MaterialTheme.typography.bodyMedium,
            color    = NxInk.main,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        NxIconButton(NxIcon.ChevronRight, s.text("editorSlotGridColumnsIncrease"), onClick = onIncrease, iconSize = 16.dp)
    }
}

