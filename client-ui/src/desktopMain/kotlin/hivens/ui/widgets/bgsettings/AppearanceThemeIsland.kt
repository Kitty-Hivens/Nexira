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
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme

// Right island of the Appearance studio: the theme axis (dark/light, its source,
// whether the palette is seeded from the wallpaper, UI style, and a jump to the full
// theme picker) beside the wallpaper controls, over the live preview. Editing the
// wallpaper re-tints everything here while the seeding switch is on; this island owns
// the choices Monet does not make.
@Composable
internal fun AppearanceThemeIsland(
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    paletteFromWallpaper: Boolean,
    onPaletteFromWallpaperChanged: (Boolean) -> Unit,
    surfaceBlur: Boolean,
    onSurfaceBlurChanged: (Boolean) -> Unit,
    onOpenThemePicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    // Optimistic local so the switch flips at once while the reveal runs; re-keyed on
    // isDarkTheme so an automatic (system / wallpaper) flip also moves it.
    var dark by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }

    NxSurface(NxSurfaceLevel.Floating, modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DayNightRow(checked = dark, title = s.settingsDarkTheme, description = s.settingsDarkThemeDesc) {
                dark = it; onToggleDarkTheme()
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
                        color = NxTheme.colors.textSecondary,
                    )
                }
            }

            // Palette source, the axis under the theme source: on, the wallpaper seeds
            // the base palette and a preset lands tinted on top of it; off, the preset
            // is the palette. Sits above the picker row so the choice is made before
            // walking into it.
            NxToggle(
                label           = s.settingsPaletteFromWallpaper,
                checked         = paletteFromWallpaper,
                description     = s.settingsPaletteFromWallpaperDesc,
                icon            = NxIcon.Palette,
                accent          = NxTheme.colors.primary,
                onCheckedChange = onPaletteFromWallpaperChanged,
            )

            // A backdrop filter is recomputed every frame by construction, so the
            // one place it can be spent or saved is here rather than per surface.
            NxToggle(
                label           = s.settingsSurfaceBlur,
                checked         = surfaceBlur,
                description     = s.settingsSurfaceBlurDesc,
                icon            = NxIcon.Layers,
                accent          = NxTheme.colors.primary,
                onCheckedChange = onSurfaceBlurChanged,
            )

            NxRow(
                title    = s.settingsThemePicker,
                subtitle = s.settingsThemePickerSub,
                icon     = NxIcon.Star,
                iconTint = NxTheme.colors.primary,
                onClick  = onOpenThemePicker,
                trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxTheme.colors.primary) },
            )
        }
    }
}
