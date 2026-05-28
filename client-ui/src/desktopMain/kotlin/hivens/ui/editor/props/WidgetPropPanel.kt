package hivens.ui.editor.props

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.editor.EditModeController
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.PropHidden
import hivens.widget.model.PropLabel
import hivens.widget.model.SlotPath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

// Right-edge prop editor. Opened by a widget's "tune" chrome affordance,
// which sets the host's prop target (path + instanceId). Resolves the
// live instance from the layout graph each recomposition so external
// edits (or a reset) reflect immediately. Mounted at the same edge as
// the palette; the host hides the palette while this is open so they do
// not overlap.
@Composable
fun WidgetPropPanel(
    visible: Boolean,
    path: SlotPath?,
    instanceId: String?,
    controller: EditModeController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalLayoutGraph.current
    val registry = LocalWidgetRegistry.current

    val instance: WidgetInstance? = if (path != null && instanceId != null) {
        graph.traverse(path)?.widgets?.firstOrNull { it.instanceId == instanceId }
    } else {
        null
    }
    val descriptor = instance?.let { registry[it.kind] }
    val serializer = descriptor?.propsSerializer

    AnimatedVisibility(
        visible  = visible && serializer != null,
        enter    = fadeIn(spring()) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        exit     = fadeOut(spring()) + slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        modifier = modifier,
    ) {
        // All non-null inside the gate; an explicit check keeps smart-cast
        // happy and survives the exit frame where the target may have just
        // vanished (widget removed while editing).
        if (descriptor != null && serializer != null && instance != null &&
            path != null && instanceId != null
        ) {
            PropPanelBody(
                descriptor = descriptor,
                serializer = serializer,
                instance   = instance,
                path       = path,
                instanceId = instanceId,
                controller = controller,
                onDismiss  = onDismiss,
            )
        }
    }
}

@Composable
private fun PropPanelBody(
    descriptor: WidgetDescriptor,
    serializer: KSerializer<*>,
    instance: WidgetInstance,
    path: SlotPath,
    instanceId: String,
    controller: EditModeController,
    onDismiss: () -> Unit,
) {
    val sd = serializer.descriptor
    // Effective values: the encoded default baseline overlaid with the
    // instance's stored overrides. Every key is present, so each field's
    // current value is non-null.
    val effective: JsonObject = remember(descriptor.defaultPropsJson, instance.props) {
        JsonObject(descriptor.defaultPropsJson + instance.props)
    }

    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .padding(top = 64.dp, bottom = 96.dp, end = 16.dp)
            .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.86f)),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier              = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Default.Tune,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.primary,
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = descriptor.displayName,
                    style      = MaterialTheme.typography.titleSmall,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint               = CelestiaTheme.colors.textSecondary,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }

        Column(
            modifier            = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in 0 until sd.elementsCount) {
                val anns = sd.getElementAnnotations(i)
                if (anns.any { it is PropHidden }) continue
                val name = sd.getElementName(i)
                val cur = effective[name] ?: continue
                val label = anns.filterIsInstance<PropLabel>().firstOrNull()?.value ?: name
                PropFieldRow(
                    label       = label,
                    element     = sd.getElementDescriptor(i),
                    annotations = anns,
                    current     = cur,
                    onChange    = { newValue ->
                        controller.updateProps(path, instanceId, JsonObject(effective + (name to newValue)))
                    },
                )
            }
        }

        TextButton(
            onClick  = { controller.updateProps(path, instanceId, JsonObject(emptyMap())) },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Сбросить к умолчанию", style = MaterialTheme.typography.labelMedium)
        }
    }
}
