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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.customization.LabeledSlider
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.resolveSurface
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.PropHidden
import hivens.widget.model.PropLabel
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceCorners
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse
import kotlin.math.roundToInt
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

    // Latch the last real target so the slide-out animation still has
    // content: on dismiss / edit-mode exit the host clears propTarget the
    // same frame `visible` goes false, which would otherwise blank the
    // panel mid-animation. The conditional write converges (same value on
    // re-composition) and only updates while a target is set.
    var lastPath by remember { mutableStateOf<SlotPath?>(null) }
    var lastId   by remember { mutableStateOf<String?>(null) }
    if (path != null && instanceId != null) {
        lastPath = path
        lastId   = instanceId
    }
    val resolvePath = path ?: lastPath
    val resolveId   = instanceId ?: lastId

    val instance: WidgetInstance? = if (resolvePath != null && resolveId != null) {
        graph.traverse(resolvePath)?.widgets?.firstOrNull { it.instanceId == resolveId }
    } else {
        null
    }
    val descriptor = instance?.let { registry[it.kind] }
    val serializer = descriptor?.propsSerializer

    AnimatedVisibility(
        // Shown for ANY targeted widget, propless included -- the Backing
        // section (per-widget glass / corner / padding) is universal, and the
        // typed-prop section only renders when the widget has a props class.
        visible  = visible && descriptor != null,
        enter    = fadeIn(spring()) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        exit     = fadeOut(spring()) + slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        modifier = modifier,
    ) {
        // Non-null inside the gate; an explicit check keeps smart-cast happy and
        // survives the exit frame where the target may have just vanished
        // (dismiss / edit-mode exit / widget removed). Uses the latched resolve*
        // so exit renders the last content. serializer stays nullable (propless).
        if (descriptor != null && resolvePath != null && resolveId != null
        ) {
            PropPanelBody(
                descriptor = descriptor,
                serializer = serializer,
                instance   = instance,
                path       = resolvePath,
                instanceId = resolveId,
                controller = controller,
                onDismiss  = onDismiss,
            )
        }
    }
}

@Composable
private fun PropPanelBody(
    descriptor: WidgetDescriptor,
    serializer: KSerializer<*>?,
    instance: WidgetInstance,
    path: SlotPath,
    instanceId: String,
    controller: EditModeController,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    val sd = serializer?.descriptor
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
            .shadow(elevation = style.panelElevation, shape = RoundedCornerShape(style.panelCorner))
            .clip(RoundedCornerShape(style.panelCorner))
            // Solid surface, no glass: a settings panel must stay readable and
            // not composite with the layers it floats over.
            .background(NxTheme.colors.surface),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier              = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon = NxIcon.Tune,
                    contentDescription = null,
                    tint               = NxTheme.colors.primary,
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = s.widgetLabel(descriptor.displayName),
                    style      = MaterialTheme.typography.titleSmall,
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Symbol(icon = NxIcon.Close,
                    contentDescription = s.editorClose,
                    tint               = NxTheme.colors.textSecondary,
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
            if (sd != null) {
                for (i in 0 until sd.elementsCount) {
                    val anns = sd.getElementAnnotations(i)
                    if (anns.any { it is PropHidden }) continue
                    val name = sd.getElementName(i)
                    val cur = effective[name] ?: continue
                    val label = s.widgetLabel(anns.filterIsInstance<PropLabel>().firstOrNull()?.value ?: name)
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
                Spacer(Modifier.size(8.dp))
            }

            // The widget's own surface, as the seven values it is. Available on
            // every widget, propless included. Each row writes one field and leaves
            // the rest alone, so nothing here can move something the eye is not on.
            Text(
                text       = s.editorBackingTitle,
                style      = MaterialTheme.typography.labelMedium,
                color      = NxTheme.colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            // Seeded through the same resolution the renderer uses, so the sliders
            // open where the plane on screen actually is.
            val surface = descriptor.resolveSurface(instance) ?: SurfaceSpec()
            fun write(next: SurfaceSpec) = controller.updateSurface(path, instanceId, next)
            val corner = style.cardCorner.value

            // One field, a value or a name. Blank follows the theme, a rung name
            // tracks the palette, a literal does not; a typo falls back to the theme
            // rather than to black, so a mistake never looks deliberate.
            StringRow(s.editorSurfaceFill, surface.fill) { write(surface.copy(fill = it)) }
            Text(
                text  = s.editorSurfaceFillHint,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
            )
            LabeledSlider(
                label         = s.editorSurfaceOpacity,
                value         = (surface.opacity ?: 1f) * 100f,
                range         = 0f..100f,
                format        = "%.0f%%",
                keyStep       = 1f,
                onValueChange = { write(surface.copy(opacity = it / 100f)) },
            )
            LabeledSlider(
                label         = s.editorSurfaceBlur,
                value         = surface.blurDp ?: 0f,
                range         = 0f..40f,
                format        = "%.0f",
                keyStep       = 1f,
                onValueChange = { write(surface.copy(blurDp = it)) },
            )
            // Opens on the style's card corner rather than on a sentinel, and writes
            // the baseline WITHOUT clearing the per-corner overrides beside it: it
            // used to replace the whole record, so moving this slider silently threw
            // away corners set one at a time.
            LabeledSlider(
                label         = s.editorBackingCorner,
                value         = surface.shape.corners.all ?: corner,
                range         = 0f..40f,
                format        = "%.0f",
                keyStep       = 1f,
                onValueChange = {
                    write(surface.copy(shape = surface.shape.copy(corners = surface.shape.corners.copy(all = it))))
                },
            )
            LabeledSlider(
                label         = s.editorBackingPadding,
                value         = surface.padding.all ?: 0f,
                range         = 0f..32f,
                format        = "%.0f",
                keyStep       = 1f,
                onValueChange = { write(surface.copy(padding = surface.padding.copy(all = it))) },
            )

            // Everything past this point is a refinement of one of the rows above.
            // Shown on request rather than always: the panel had eleven rows for a
            // plane most widgets set two of, and a wall of controls is its own way of
            // hiding the ones that matter.
            var showMore by remember { mutableStateOf(false) }
            DisclosureRow(s.editorSurfaceMore, showMore) { showMore = !showMore }
            if (showMore) {
                StringRow(s.editorSurfaceShapeKind, surface.shape.kind) {
                    write(surface.copy(shape = surface.shape.copy(kind = it)))
                }
                Text(
                    text  = s.editorSurfaceShapeKindHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
                )
                LabeledSlider(
                    label         = s.editorSurfaceSmoothing,
                    value         = (surface.shape.smoothing ?: 0f) * 100f,
                    range         = 0f..100f,
                    format        = "%.0f%%",
                    keyStep       = 1f,
                    onValueChange = { write(surface.copy(shape = surface.shape.copy(smoothing = it / 100f))) },
                )
                // A star's three values, and only where they mean something. Offering
                // them under a rounded rectangle would put back the thing this panel
                // was collapsed to remove: rows that move nothing.
                val kind = surface.shape.kind.trim().lowercase()
                if (kind == "star" || kind == "polygon") {
                    LabeledSlider(
                        label         = s.editorSurfaceShapePoints,
                        value         = (surface.shape.points ?: if (kind == "star") 5 else 6).toFloat(),
                        range         = 3f..16f,
                        format        = "%.0f",
                        keyStep       = 1f,
                        onValueChange = { write(surface.copy(shape = surface.shape.copy(points = it.roundToInt()))) },
                    )
                    if (kind == "star") {
                        LabeledSlider(
                            label         = s.editorSurfaceShapeInnerRadius,
                            value         = (surface.shape.innerRadius ?: 0.5f) * 100f,
                            range         = 5f..95f,
                            format        = "%.0f%%",
                            keyStep       = 1f,
                            onValueChange = { write(surface.copy(shape = surface.shape.copy(innerRadius = it / 100f))) },
                        )
                    }
                    LabeledSlider(
                        label         = s.editorSurfaceShapePointRounding,
                        value         = (surface.shape.pointRounding ?: 0f) * 100f,
                        range         = 0f..100f,
                        format        = "%.0f%%",
                        keyStep       = 1f,
                        onValueChange = { write(surface.copy(shape = surface.shape.copy(pointRounding = it / 100f))) },
                    )
                }
                // Each corner opens at whatever the baseline resolves to and, once
                // moved, pins that corner independently -- which is what makes a plane
                // square on one side and round on the other.
                CornerRow(s.editorSurfaceCornerTopStart, surface.shape.corners.topStart(corner)) {
                    write(surface.copy(shape = surface.shape.copy(corners = surface.shape.corners.copy(topStart = it))))
                }
                CornerRow(s.editorSurfaceCornerTopEnd, surface.shape.corners.topEnd(corner)) {
                    write(surface.copy(shape = surface.shape.copy(corners = surface.shape.corners.copy(topEnd = it))))
                }
                CornerRow(s.editorSurfaceCornerBottomEnd, surface.shape.corners.bottomEnd(corner)) {
                    write(surface.copy(shape = surface.shape.copy(corners = surface.shape.corners.copy(bottomEnd = it))))
                }
                CornerRow(s.editorSurfaceCornerBottomStart, surface.shape.corners.bottomStart(corner)) {
                    write(surface.copy(shape = surface.shape.copy(corners = surface.shape.corners.copy(bottomStart = it))))
                }
                LabeledSlider(
                    label         = s.editorSurfaceBorder,
                    value         = surface.border.widthDp ?: 0f,
                    range         = 0f..6f,
                    format        = "%.1f",
                    keyStep       = 0.5f,
                    onValueChange = { write(surface.copy(border = surface.border.copy(widthDp = it))) },
                )
                StringRow(s.editorSurfaceBorderColor, surface.border.color) {
                    write(surface.copy(border = surface.border.copy(color = it)))
                }
                LabeledSlider(
                    label         = s.editorSurfaceBorderOpacity,
                    value         = (surface.border.opacity ?: 1f) * 100f,
                    range         = 0f..100f,
                    format        = "%.0f%%",
                    keyStep       = 1f,
                    onValueChange = { write(surface.copy(border = surface.border.copy(opacity = it / 100f))) },
                )
                LabeledSlider(
                    label         = s.editorSurfaceShadow,
                    value         = surface.shadowDp ?: 0f,
                    range         = 0f..24f,
                    format        = "%.0f",
                    keyStep       = 1f,
                    onValueChange = { write(surface.copy(shadowDp = it)) },
                )
                // Per-side padding. Each opens at the uniform value and, once moved,
                // pins that side independently of it.
                CornerRow(s.editorBackingPaddingTop, surface.padding.top(0f)) {
                    write(surface.copy(padding = surface.padding.copy(top = it)))
                }
                CornerRow(s.editorBackingPaddingEnd, surface.padding.end(0f)) {
                    write(surface.copy(padding = surface.padding.copy(end = it)))
                }
                CornerRow(s.editorBackingPaddingBottom, surface.padding.bottom(0f)) {
                    write(surface.copy(padding = surface.padding.copy(bottom = it)))
                }
                CornerRow(s.editorBackingPaddingStart, surface.padding.start(0f)) {
                    write(surface.copy(padding = surface.padding.copy(start = it)))
                }
            }
        }

        TextButton(
            onClick  = {
                if (sd != null) controller.updateProps(path, instanceId, JsonObject(emptyMap()))
                controller.updateSurface(path, instanceId, null)
            },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Symbol(NxIcon.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.editorResetToDefault, style = MaterialTheme.typography.labelMedium)
        }
    }
}
