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
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxToggle
import hivens.ui.surface.NxSurface
import hivens.ui.theme.NxColor
import hivens.ui.surface.SurfaceKind
import hivens.widget.api.SlotRenderer
import hivens.widget.api.childGap
import hivens.widget.api.withoutLeadingGap
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

// Right island of the Appearance studio beside the wallpaper controls, over the live
// preview: whether surfaces blur, and around it the slots the look's providers' controls
// are drawn in (dark and light, what decides it, the way to the theme picker, and
// whether motion is reduced), which hold nothing where they are absent or their
// provider does not draw. The wallpaper does not colour the theme: it is a picture
// under the interface, darkened with the theme's own page colour.
@Composable
internal fun AppearanceThemeIsland(
    surfaceBlur: Boolean,
    onSurfaceBlurChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()

    // Each row takes its gap itself rather than from the column's spacing, so a slot whose
    // controls are absent takes none, the first one included.
    val gap = Modifier.childGap(ROW_GAP, Orientation.Vertical)
    NxSurface(SurfaceKind.Panel, modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).withoutLeadingGap(ROW_GAP, Orientation.Vertical),
        ) {
            SlotRenderer(SurfaceId(BG_SETTINGS_SURFACE), SlotId("theme"), gap, spacing = ROW_GAP)

            // A backdrop filter is recomputed every frame by construction, so the
            // one place it can be spent or saved is here rather than per surface.
            NxToggle(
                label           = s.text("settingsSurfaceBlur"),
                checked         = surfaceBlur,
                modifier        = gap,
                description     = s.text("settingsSurfaceBlurDesc"),
                icon            = NxIcon.Layers,
                accent          = NxColor.lead(),
                onCheckedChange = onSurfaceBlurChanged,
            )

            // Beside the blur because it is the same kind of choice: decoration that
            // costs something, switched off in one place rather than widget by widget.
            SlotRenderer(SurfaceId(BG_SETTINGS_SURFACE), SlotId("motion"), gap, spacing = ROW_GAP)

            ModuleEffectRows(gap)

            SlotRenderer(SurfaceId(BG_SETTINGS_SURFACE), SlotId("theme.link"), gap)
        }
    }
}

/** The gap between the island's rows, and between the controls in its theme slot. */
private val ROW_GAP = 16.dp
