package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
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
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

// Right island of the Appearance studio beside the wallpaper controls, over the live
// preview: whether surfaces blur and whether motion is reduced, and around them the
// slots the colour provider's controls are drawn in (dark and light, what decides it,
// and the way to the theme picker), which hold nothing where they are absent or their
// provider does not draw. The wallpaper does not colour the theme: it is a picture
// under the interface, darkened with the theme's own page colour.
@Composable
internal fun AppearanceThemeIsland(
    surfaceBlur: Boolean,
    onSurfaceBlurChanged: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()

    NxSurface(SurfaceKind.Panel, modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SlotRenderer(SurfaceId(BG_SETTINGS_SURFACE), SlotId("theme"), spacing = 16.dp)

            // A backdrop filter is recomputed every frame by construction, so the
            // one place it can be spent or saved is here rather than per surface.
            NxToggle(
                label           = s.text("settingsSurfaceBlur"),
                checked         = surfaceBlur,
                description     = s.text("settingsSurfaceBlurDesc"),
                icon            = NxIcon.Layers,
                accent          = NxColor.lead(),
                onCheckedChange = onSurfaceBlurChanged,
            )

            // Beside the blur because it is the same kind of choice: decoration that
            // costs something, switched off in one place rather than widget by widget.
            NxToggle(
                label           = s.text("settingsReduceMotion"),
                checked         = reduceMotion,
                description     = s.text("settingsReduceMotionDesc"),
                icon            = NxIcon.Speed,
                accent          = NxColor.lead(),
                onCheckedChange = onReduceMotionChanged,
            )

            ModuleEffectRows()

            SlotRenderer(SurfaceId(BG_SETTINGS_SURFACE), SlotId("theme.link"))
        }
    }
}
