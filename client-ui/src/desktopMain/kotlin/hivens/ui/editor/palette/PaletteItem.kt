package hivens.ui.editor.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
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
    previews: WidgetPreviewHost,
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

    val preview = rememberWidgetPreview(previews, descriptor.kind)
    Column(
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
                // The picture the tile is showing, carried out under the pointer.
                // A ghost that is the widget answers "what am I placing" in the
                // same breath as "how much room does it take".
                ghost                 = {
                    PaletteGhost(displayName = label, sizing = descriptor.sizing, preview = preview)
                },
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
            .padding(6.dp),
    ) {
        WidgetThumbnail(preview = preview, label = label, sizing = descriptor.sizing)
        Spacer(Modifier.height(5.dp))
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelMedium,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
        Text(
            // The footprint where the widget named one, its kind where it did not.
            // Both are the same question asked of the tile: what is this, and how
            // much of my surface is it about to take.
            text       = descriptor.sizing.footprintLabel() ?: descriptor.kind.value,
            style      = MaterialTheme.typography.labelSmall,
            color      = NxTheme.colors.textSecondary,
            fontFamily = LocalMonoFamily.current,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
    }
}

/** "200x230" for a widget that declared a preferred size, null for one that did not. */
private fun WidgetSizing.footprintLabel(): String? =
    if (prefWidth > 0 && prefHeight > 0) "${prefWidth}x$prefHeight" else null

/**
 * The picture on a tile.
 *
 * The widget itself once it has been drawn, letterboxed into the tile at its own
 * proportions so a column reads as a column and a token as a token. Its letter
 * until then, and for good if it declined to compose: a widget is free to need a
 * context the palette cannot hand it, and a blank tile would say less than the
 * letter the palette has always shown.
 */
@Composable
private fun WidgetThumbnail(preview: WidgetPreview, label: String, sizing: WidgetSizing) {
    // The widget's own proportions, within what a tile can hold. A fixed box put a
    // 340 by 48 control in the middle of two thirds of nothing, and a clock at 200
    // by 230 into a letterbox. Held between the two so one very long widget cannot
    // squash its whole row, and one very tall one cannot own the panel.
    val ratio = when (preview) {
        is WidgetPreview.Drawn -> (preview.inkSize.width.toFloat() / preview.inkSize.height)
            .coerceIn(MIN_THUMB_RATIO, MAX_THUMB_RATIO)
        else -> DEFAULT_THUMB_RATIO
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp))
            .background(NxTheme.colors.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        when (preview) {
            is WidgetPreview.Drawn -> Image(
                painter            = remember(preview) {
                    // The widget's own rectangle out of the scene's frame. Fit
                    // inside it, not crop: the whole widget or nothing, because a
                    // cropped preview of a wide widget is a picture of its middle.
                    BitmapPainter(preview.image, preview.inkOffset, preview.inkSize)
                },
                contentDescription = label,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize().padding(4.dp),
            )
            else -> Box(
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
        }
    }
}

/**
 * How far a tile may depart from its widget's own shape.
 *
 * The floor keeps a tall widget from taking a whole panel of height for itself,
 * the ceiling keeps a long thin one from flattening the row it shares. The
 * default is for a tile with nothing in it yet, and is a shape that suits a
 * letter.
 */
private const val MIN_THUMB_RATIO = 0.85f
private const val MAX_THUMB_RATIO = 2.6f
private const val DEFAULT_THUMB_RATIO = 1.45f

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
private fun PaletteGhost(displayName: String, sizing: WidgetSizing, preview: WidgetPreview) {
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
        // The widget itself where the gallery has it, at the size it will land at,
        // so what is under the pointer is what the slot is about to hold. Faded,
        // because it is not there yet.
        if (preview is WidgetPreview.Drawn) {
            Image(
                painter            = remember(preview) {
                    BitmapPainter(preview.image, preview.inkOffset, preview.inkSize)
                },
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                alpha              = 0.85f,
                modifier           = Modifier.fillMaxSize().padding(2.dp),
            )
        } else {
            PaletteGhostChip(displayName)
        }
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
