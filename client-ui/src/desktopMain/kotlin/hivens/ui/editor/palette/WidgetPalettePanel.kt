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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
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
    var paletteOffset by remember { mutableStateOf(Offset.Zero) }
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
                    translationX = paletteOffset.x
                    translationY = paletteOffset.y
                    // Fade out of the way while a widget is dragged so the drop
                    // zone under the dock stays visible. Folded into this one
                    // layer (not a separate .alpha modifier) so the glass
                    // composites uniformly rather than as banded sub-layers.
                    alpha = if (dimmed) 0.12f else 1f
                }
                .width(280.dp)
                .fillMaxHeight()
                .padding(top = 64.dp, bottom = 96.dp, end = 16.dp, start = 0.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                // Near-opaque so the busy backdrop (art + right rail) does not
                // bleed through unevenly and read as mismatched glass.
                .background(glassSurfaceAlpha(0.94f)),
        ) {
            // Header
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            paletteOffset += drag
                        }
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = Icons.Default.Widgets,
                        contentDescription = null,
                        tint               = CelestiaTheme.colors.primary,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = s.editorWidgets,
                        style      = MaterialTheme.typography.titleSmall,
                        color      = CelestiaTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = "${filtered.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = s.editorPaletteHide,
                        tint               = CelestiaTheme.colors.textSecondary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text     = s.editorPaletteHint,
                style    = MaterialTheme.typography.labelSmall,
                color    = CelestiaTheme.colors.textSecondary,
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
                        color = CelestiaTheme.colors.textSecondary,
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
                            color = CelestiaTheme.colors.textSecondary,
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
            .border(1.dp, glassSurfaceAlpha(0.5f), RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector        = Icons.Default.Search,
            contentDescription = null,
            tint               = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value         = query,
                onValueChange = onQueryChange,
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodySmall.copy(color = CelestiaTheme.colors.textPrimary),
                cursorBrush   = SolidColor(CelestiaTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    text  = s.editorPaletteSearch,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
                )
            }
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(22.dp)) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.textSecondary,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}
