package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.ThemeMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxToggle
import hivens.ui.screens.settings.DayNightRow
import hivens.ui.surface.NxSurface
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Theme
import hivens.ui.surface.SurfaceKind

// Right island of the Appearance studio: the theme axis (dark/light, its source,
// whether surfaces blur, and a jump to the theme picker) beside the wallpaper
// controls, over the live preview. The wallpaper does not colour the theme: it is a
// picture under the interface, darkened with the theme's own page colour.
@Composable
internal fun AppearanceThemeIsland(
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    activeTheme: Theme,
    surfaceBlur: Boolean,
    onSurfaceBlurChanged: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChanged: (Boolean) -> Unit,
    onOpenThemePicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Optimistic local so the switch flips at once while the reveal runs; re-keyed on
    // isDarkTheme so an automatic (system / wallpaper) flip also moves it.
    var dark by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }

    NxSurface(SurfaceKind.Panel, modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DayNightRow(checked = dark, title = s.settingsDarkTheme, description = s.settingsDarkThemeDesc) {
                dark = it; onToggleDarkTheme()
            }
            // A theme without a light scheme stays dark in light mode rather than
            // having one invented for it, so the switch says why nothing changed.
            if (!activeTheme.hasLight) {
                Text(
                    text  = s.themePickerDarkOnly,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxInk.quiet,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BgPicker(s.settingsThemeModeTitle) {
                    NxChoiceChip(s.settingsThemeModeManual, themeMode == ThemeMode.Manual) {
                        onThemeModeChanged(ThemeMode.Manual)
                    }
                    NxChoiceChip(
                        s.settingsThemeModeSystem,
                        themeMode == ThemeMode.System,
                        enabled = systemThemeAvailable,
                    ) { onThemeModeChanged(ThemeMode.System) }
                    NxChoiceChip(s.settingsThemeModeWallpaper, themeMode == ThemeMode.Wallpaper) {
                        onThemeModeChanged(ThemeMode.Wallpaper)
                    }
                }
                if (!systemThemeAvailable) {
                    Text(
                        text  = s.settingsThemeModeSystemUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = NxInk.quiet,
                    )
                }
            }

            // A backdrop filter is recomputed every frame by construction, so the
            // one place it can be spent or saved is here rather than per surface.
            NxToggle(
                label           = s.settingsSurfaceBlur,
                checked         = surfaceBlur,
                description     = s.settingsSurfaceBlurDesc,
                icon            = NxIcon.Layers,
                accent          = NxColor.lead(),
                onCheckedChange = onSurfaceBlurChanged,
            )

            // Beside the blur because it is the same kind of choice: decoration that
            // costs something, switched off in one place rather than widget by widget.
            NxToggle(
                label           = s.settingsReduceMotion,
                checked         = reduceMotion,
                description     = s.settingsReduceMotionDesc,
                icon            = NxIcon.Speed,
                accent          = NxColor.lead(),
                onCheckedChange = onReduceMotionChanged,
            )

            NxRow(
                title    = s.settingsThemePicker,
                subtitle = s.settingsThemePickerSub,
                icon     = NxIcon.Star,
                iconTint = NxColor.lead(),
                onClick  = onOpenThemePicker,
                trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxColor.lead()) },
            )
        }
    }
}
