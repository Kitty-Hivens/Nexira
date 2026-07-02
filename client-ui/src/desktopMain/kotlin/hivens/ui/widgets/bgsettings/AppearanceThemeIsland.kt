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
import hivens.core.data.UiStyle
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxRow
import hivens.ui.screens.settings.DayNightRow
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme

// Right island of the Appearance studio: the theme axis (dark/light, its source,
// UI style, and a jump to the full theme picker) beside the wallpaper controls, over the
// live preview. The palette itself is already seeded from the wallpaper by default
// (Monet), so editing the wallpaper re-tints everything here; this island owns the
// dark/light + style choices that Monet does not.
@Composable
internal fun AppearanceThemeIsland(
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
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

            BgPicker(s.settingsUiStyleTitle) {
                NxChoiceChip(s.settingsUiStyleCelestia, uiStyle == UiStyle.Celestia) { onUiStyleChanged(UiStyle.Celestia) }
                NxChoiceChip(s.settingsUiStyleBrut,     uiStyle == UiStyle.Brut)     { onUiStyleChanged(UiStyle.Brut) }
            }

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
