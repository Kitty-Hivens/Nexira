package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.widget.api.SlotRenderer
import hivens.widget.api.childGap
import hivens.widget.api.withoutLeadingGap
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance

// Right island of the Appearance studio beside the wallpaper controls, over the live
// preview: the slots the look's providers' controls are drawn in (dark and light, what
// decides it, whether motion is reduced, the way to the theme picker), which hold
// nothing where they are absent or their provider does not draw.
// The wallpaper does not colour the theme: it is a picture under the interface,
// darkened with the theme's own page colour.
@Composable
internal fun AppearanceThemeIsland(instance: WidgetInstance, modifier: Modifier = Modifier) {
    // Each row takes its gap itself rather than from the column's spacing, so a slot whose
    // controls are absent takes none, the first one included.
    val gap = Modifier.childGap(ROW_GAP, Orientation.Vertical)
    NxSurface(SurfaceKind.Panel, modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).withoutLeadingGap(ROW_GAP, Orientation.Vertical),
        ) {
            SlotRenderer(instance, SlotId("theme"), gap, spacing = ROW_GAP)

            SlotRenderer(instance, SlotId("motion"), gap, spacing = ROW_GAP)

            ModuleEffectRows(gap)

            SlotRenderer(instance, SlotId("theme.link"), gap)
        }
    }
}

/** The gap between the island's rows, and between the controls in its theme slot. */
private val ROW_GAP = 16.dp
