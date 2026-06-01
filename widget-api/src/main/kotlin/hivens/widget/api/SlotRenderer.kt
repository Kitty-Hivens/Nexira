package hivens.widget.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        SlotOrientation.Row -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            slotControl(path, content)
            content.widgets.forEachIndexed { index, instance ->
                val descriptor = registry[instance.kind]
                if (descriptor != null) {
                    if (instance.weight > 0f) {
                        Box(Modifier.weight(instance.weight)) {
                            decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                        }
                    } else {
                        decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                    }
                }
                if (index < content.widgets.lastIndex) slotDivider(path, content, index)
            }
        }
        SlotOrientation.Grid -> Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
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
        SlotOrientation.Canvas -> Box(modifier) {
            slotControl(path, content)
            // Free canvas: each widget at its absolute dp offset + size, painted
            // in z-order then index. Overlap is natural in a Box. No dividers.
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
        // Column.
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
            slotControl(path, content)
            content.widgets.forEachIndexed { index, instance ->
                val descriptor = registry[instance.kind]
                if (descriptor != null) {
                    if (instance.weight > 0f) {
                        Box(Modifier.weight(instance.weight)) {
                            decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
                        }
                    } else {
                        decorator(address, index, descriptor, instance) { RenderWidget(descriptor, instance) }
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
@Composable
private fun RenderWidget(descriptor: WidgetDescriptor, instance: WidgetInstance) {
    val chrome = instance.chrome
    if (chrome == null) {
        descriptor.Render(instance)
    } else {
        LocalWidgetChromeRenderer.current(chrome) { descriptor.Render(instance) }
    }
}
