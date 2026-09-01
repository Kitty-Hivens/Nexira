package hivens.ui.editor.palette

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.positionChange
import hivens.ui.editor.rememberDockSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.Form
import hivens.ui.editor.rememberDockOffset
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalWidgetRegistry

// Floating widget palette. Right-edge pinned. Slides in/out with the
// surrounding edit-mode toggle. Sorted alphabetically by displayName
// so the list is a stable reading experience.
@Composable
fun WidgetPalettePanel(
    visible: Boolean,
    dimmed: Boolean = false,
    onDismiss: () -> Unit,
    controller: DragController,
    registry: DropTargetRegistry,
    editController: EditModeController,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val registry0 = LocalWidgetRegistry.current
    // Draggable dock: the header drags this offset (session-scoped).
    val paletteOffset = rememberDockOffset()
    val paletteSize   = rememberDockSize(default = 280.dp)
    // Only removable descriptors enter the palette. Non-removable
    // widgets (the auth panel, the three shell regions) are
    // surface-essential: shipping a default layout pins exactly one
    // instance, and the chrome hides the remove button for them. If
    // the palette also exposed them, the user could drop duplicates
    // into arbitrary slots and never be able to remove them.
    val descriptors = remember(registry0, s) {
        registry0.all().values
            .filter { it.removable }
            .sortedBy { s.widgetLabel(it.displayName).lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(descriptors, query, s) {
        if (query.isBlank()) descriptors
        else descriptors.filter {
            s.widgetLabel(it.displayName).contains(query, ignoreCase = true) || it.kind.value.contains(query, ignoreCase = true)
        }
    }

    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(spring()) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        exit     = fadeOut(spring()) + slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    translationX = paletteOffset.value.x
                    translationY = paletteOffset.value.y
                    // Fade out of the way while a widget is dragged so the drop
                    // zone under the dock stays visible. Folded into this one
                    // layer (not a separate .alpha modifier) so the glass
                    // composites uniformly rather than as banded sub-layers.
                    alpha = if (dimmed) 0.12f else 1f
                }
                .width(paletteSize.width)
                // The panel is anchored to the right edge, so pulling left widens it.
                // Secondary button, because the primary one is already the drag that
                // moves the panel and the drag that lifts a widget out of it.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                            val id = event.changes.first().id
                            event.changes.forEach { it.consume() }
                            drag(id) { change ->
                                paletteSize.resize(-change.positionChange().x.toDp())
                                change.consume()
                            }
                        }
                    }
                }
                .fillMaxHeight()
                .padding(top = 64.dp, bottom = 96.dp, end = 16.dp, start = 0.dp)
                .shadow(elevation = Form.panelElevation, shape = RoundedCornerShape(Form.panelCorner))
                .clip(RoundedCornerShape(Form.panelCorner))
                // Solid surface, no glass: the panel floats over the right rail,
                // and stacked translucent layers composited into muddy glass.
                .background(NxTheme.colors.surface),
        ) {
            // Header
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // No slop: a header is a handle. detectDragGestures holds the
                        // first few pixels back before it reports anything, which on a
                        // short strip reads as the panel refusing to be grabbed. The
                        // editor's own widget drag starts on the press for this reason.
                        // requireUnconsumed yields to the close button sitting in here.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            drag(down.id) { change ->
                                change.consume()
                                paletteOffset.drag(change.positionChange())
                            }
                        }
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Symbol(icon = NxIcon.Widgets,
                        contentDescription = null,
                        tint               = NxTheme.colors.primary,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = s.editorWidgets,
                        style      = MaterialTheme.typography.titleSmall,
                        color      = NxTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = "${filtered.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Symbol(icon = NxIcon.Close,
                        contentDescription = s.editorPaletteHide,
                        tint               = NxTheme.colors.textSecondary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text     = s.editorPaletteHint,
                style    = MaterialTheme.typography.labelSmall,
                color    = NxTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
            )
            Spacer(Modifier.height(6.dp))

            if (descriptors.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = s.editorPaletteEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.textSecondary,
                    )
                }
            } else {
                PaletteSearchField(query = query, onQueryChange = { query = it })
                Spacer(Modifier.height(6.dp))
                if (filtered.isEmpty()) {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = s.editorPaletteNoMatch,
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier            = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(items = filtered, key = { it.kind.value }) { descriptor ->
                            PaletteItem(
                                descriptor     = descriptor,
                                controller     = controller,
                                registry       = registry,
                                editController = editController,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Compact glass search field. Filters the palette by displayName / kind so the
// now-large widget set stays navigable.
@Composable
private fun PaletteSearchField(query: String, onQueryChange: (String) -> Unit) {
    val s = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, NxTheme.colors.outline, RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Symbol(icon = NxIcon.Search,
            contentDescription = null,
            tint               = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value         = query,
                onValueChange = onQueryChange,
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodySmall.copy(color = NxTheme.colors.textPrimary),
                cursorBrush   = SolidColor(NxTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    text  = s.editorPaletteSearch,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
                )
            }
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(22.dp)) {
                Symbol(icon = NxIcon.Close,
                    contentDescription = null,
                    tint               = NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}
