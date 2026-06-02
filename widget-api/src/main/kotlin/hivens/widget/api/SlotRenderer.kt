package hivens.widget.api

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.widget.model.CanvasPlacement
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse

// Renders every widget at the addressed slot. Two entry forms:
//
//   * Top-level: SlotRenderer(surface, slot) -- used by surface
//     composables (NewHomeScreen, LibraryScreen, AppLayout rails, ...).
//     Initialises LocalSlotPath at the surface root.
//
//   * Nested: SlotRenderer(parent, slot) -- used by container widgets
//     inside their @Composable body. Extends LocalSlotPath with the
//     container's instanceId so the editor's drop-target registry
//     distinguishes "slot 'body' on container X" from "slot 'body' on
//     container Y".
//
// Phase G: the slot OWNS its intra-slot layout. The renderer reads the
// slot's SlotOrientation and lays the widgets out itself (Column / Row;
// Grid renders as Column until G5), so callers no longer wrap the call in
// a Column. The surface passes `modifier` for inter-slot positioning
// (weight / fill / padding / scroll) and `spacing` for the gap between
// widgets (the arrangement the caller's Column used to set). A widget
// with weight > 0 takes a weighted share of the main axis; weight 0
// renders through the decorator directly, exactly as before.
//
// Each widget renders through LocalWidgetDecorator. Default decorator is
// identity -- zero cost when no editor is mounted. Unknown widget kinds
// render nothing; the slot stays valid and the diagnostic surfaces in
// the editor's --audit-widgets dev tool.
@Composable
fun SlotRenderer(
    surface: SurfaceId,
    slot: SlotId,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
) {
    val path = SlotPath(surface, slot)
    CompositionLocalProvider(LocalSlotPath provides path) {
        RenderSlotContent(path, modifier, spacing)
    }
}

@Composable
fun SlotRenderer(
    parent: WidgetInstance,
    slot: SlotId,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
) {
    val parentPath = LocalSlotPath.current
    val childPath = parentPath.child(parent.instanceId, slot)
    CompositionLocalProvider(LocalSlotPath provides childPath) {
        RenderSlotContent(childPath, modifier, spacing)
    }
}

@Composable
private fun RenderSlotContent(path: SlotPath, modifier: Modifier, spacing: Dp) {
    val graph = LocalLayoutGraph.current
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val emptyDecorator = LocalEmptySlotDecorator.current
    val slotControl = LocalSlotControlDecorator.current
    val slotDivider = LocalSlotDividerDecorator.current
    val motionMs = LocalSlotMotionMs.current

    val content: SlotContent = graph.traverse(path) ?: SlotContent()
    val address = path.leafAddress

    if (content.widgets.isEmpty()) {
        // Occupy the slot footprint (inter-slot sizing lives in `modifier`)
        // so an empty slot keeps its place; the empty decorator paints the
        // edit-mode placeholder, or nothing.
        Box(modifier) { emptyDecorator(address) }
        return
    }

    when (content.orientation) {
        SlotOrientation.Row -> Row(modifier.animatedReflow(motionMs), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            slotControl(path, content)
            content.widgets.forEachIndexed { index, instance ->
                val descriptor = registry[instance.kind]
                if (descriptor != null) {
                    val sizeMod = canvasSizeModifier(instance.canvas)
                    when {
                        // Weight wins over an explicit size in a flow slot: resizing
                        // a weighted widget must not strip its flex (else the
                        // weighted center region stops filling between the rails).
                        instance.weight > 0f -> Box(Modifier.weight(instance.weight)) {
                            decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                        }
                        sizeMod != null -> Box(sizeMod) {
                            decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                        }
                        else -> decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                    }
                }
                if (index < content.widgets.lastIndex) slotDivider(path, content, index)
            }
        }
        SlotOrientation.Grid -> Column(modifier.animatedReflow(motionMs), verticalArrangement = Arrangement.spacedBy(spacing)) {
            slotControl(path, content)
            // Non-lazy chunked grid: a Column of equal-width Rows. Reuses the
            // decorator path so chrome + DnD stay consistent; cells are uniform
            // (per-widget weight is ignored in Grid) and the last row is padded
            // with weighted spacers so the columns stay aligned. No dividers.
            val cols = content.gridColumns.coerceAtLeast(1)
            content.widgets.chunked(cols).forEachIndexed { rowIndex, rowWidgets ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowWidgets.forEachIndexed { colIndex, instance ->
                        val index = rowIndex * cols + colIndex
                        val descriptor = registry[instance.kind]
                        Box(Modifier.weight(1f)) {
                            if (descriptor != null) {
                                decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                            }
                        }
                    }
                    repeat(cols - rowWidgets.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        SlotOrientation.Canvas -> {
            // Publish the slot's measured size (dp) so the editor's move gesture
            // clamps a free-placed widget on-canvas. onSizeChanged keeps it
            // current without restarting the gesture.
            val density = LocalDensity.current
            val reportSlotBounds = LocalSlotBoundsReporter.current
            var slotSizeDp by remember { mutableStateOf(Size.Zero) }
            Box(
                modifier
                    .onSizeChanged { sz ->
                        slotSizeDp = with(density) { Size(sz.width.toDp().value, sz.height.toDp().value) }
                    }
                    .onGloballyPositioned { reportSlotBounds(path, it.boundsInWindow()) },
            ) {
                CompositionLocalProvider(LocalCanvasSlotSizeDp provides slotSizeDp) {
                    // Anchor the edit-mode orientation chip to the corner so it
                    // does not overlap a widget placed at (0,0).
                    Box(Modifier.align(Alignment.TopStart)) { slotControl(path, content) }
                    // Free canvas: each widget at its absolute dp offset + size,
                    // painted in z-order then index. Overlap is natural in a Box.
                    content.widgets.withIndex()
                        .sortedWith(compareBy({ it.value.canvas?.z ?: 0 }, { it.index }))
                        .forEach { (index, instance) ->
                            val descriptor = registry[instance.kind] ?: return@forEach
                            val cp = instance.canvas
                            val sizeMod = if (cp != null && cp.width > 0f && cp.height > 0f)
                                Modifier.size(cp.width.dp, cp.height.dp) else Modifier
                            Box(Modifier.offset((cp?.x ?: 0f).dp, (cp?.y ?: 0f).dp).then(sizeMod)) {
                                decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                            }
                        }
                }
            }
        }
        // Column.
        else -> Column(modifier.animatedReflow(motionMs), verticalArrangement = Arrangement.spacedBy(spacing)) {
            slotControl(path, content)
            content.widgets.forEachIndexed { index, instance ->
                val descriptor = registry[instance.kind]
                if (descriptor != null) {
                    val sizeMod = canvasSizeModifier(instance.canvas)
                    when {
                        // Weight wins over an explicit size in a flow slot: resizing
                        // a weighted widget must not strip its flex (else the
                        // weighted center region stops filling between the rails).
                        instance.weight > 0f -> Box(Modifier.weight(instance.weight)) {
                            decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                        }
                        sizeMod != null -> Box(sizeMod) {
                            decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                        }
                        else -> decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                    }
                }
                if (index < content.widgets.lastIndex) slotDivider(path, content, index)
            }
        }
    }
}

// Renders a widget, wrapped in its per-instance backing when it has one. The
// chrome wrap is inside the editor decorator (the drag handle / remove button
// surround the glass card) but is PRODUCTION styling -- it paints whenever
// instance.chrome != null, editor mounted or not.
// Explicit per-widget size for a flow (Row/Column) slot, or null when the
// widget has no canvas size set -- in which case the slot lays it out as
// before (weighted or wrap/fill). Lets the editor's resize handle size a
// widget (e.g. the music player) without switching the slot to Canvas. Only a
// resized widget carries a size, so untouched layouts are unaffected.
private fun canvasSizeModifier(cp: CanvasPlacement?): Modifier? {
    if (cp == null || (cp.width <= 0f && cp.height <= 0f)) return null
    var m: Modifier = Modifier
    if (cp.width > 0f) m = m.width(cp.width.dp)
    if (cp.height > 0f) m = m.height(cp.height.dp)
    return m
}

// Compose forbids try/catch around a @Composable invocation (compiler error),
// and there is no public per-subtree error boundary, so a single widget's
// failure can't be isolated here -- crash recovery is the shell remount
// (UiRecoverySignal) at the composition root, not a per-widget catch.
// Edit-mode reflow: animate the slot container's footprint as widgets are
// added / removed / resized so the change reads as motion, not a jump. motionMs
// 0 (production, and Brut) returns the modifier untouched -- zero cost.
private fun Modifier.animatedReflow(motionMs: Int): Modifier =
    if (motionMs > 0) this.then(Modifier.animateContentSize(tween(motionMs))) else this

@Composable
private fun RenderWidget(descriptor: WidgetDescriptor, instance: WidgetInstance) {
    val chrome = instance.chrome
    if (chrome == null) {
        descriptor.Render(instance)
    } else {
        LocalWidgetChromeRenderer.current(chrome) { descriptor.Render(instance) }
    }
}
