package hivens.ui.editor.palette

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.editor.EditModeController
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.LocalWidgetRegistry

// Floating widget palette. Right-edge pinned. Slides in/out with the
// surrounding edit-mode toggle. Sorted alphabetically by displayName
// so the list is a stable reading experience.
@Composable
fun WidgetPalettePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    controller: DragController,
    registry: DropTargetRegistry,
    editController: EditModeController,
    modifier: Modifier = Modifier,
) {
    val registry0 = LocalWidgetRegistry.current
    val descriptors = remember(registry0) {
        registry0.all().values.sortedBy { it.displayName.lowercase() }
    }

    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(spring()) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        exit     = fadeOut(spring()) + slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .padding(top = 64.dp, bottom = 96.dp, end = 16.dp, start = 0.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(glassSurfaceAlpha(0.86f)),
        ) {
            // Header
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier
                    .fillMaxWidth()
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
                        text       = "Виджеты",
                        style      = MaterialTheme.typography.titleSmall,
                        color      = CelestiaTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = "${descriptors.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Hide palette",
                        tint               = CelestiaTheme.colors.textSecondary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text     = "Перетащи в нужный слот",
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
                        text  = "Registry пуст — это build issue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier            = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = descriptors, key = { it.kind.value }) { descriptor ->
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
