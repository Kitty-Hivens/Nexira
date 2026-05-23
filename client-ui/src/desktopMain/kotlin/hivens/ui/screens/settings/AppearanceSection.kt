package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Interface + Behavior block. Drives anything user-facing about how the
 * launcher looks and how it behaves around launches: language, theme
 * preset shortcut, background shortcut, dark/light toggle, home-view
 * variant, UI style variant, close-after-launch, offline mode.
 *
 * Network bypasses live separately in [NetworkSection]; experimental
 * toggles in [ExperimentalSection].
 */
@Composable
internal fun AppearanceSection(
    form: SettingsFormState,
    save: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenThemePicker: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    currentLocale: AppLocale,
    onLocaleChanged: (AppLocale) -> Unit,
    homeView: HomeView,
    onHomeViewChanged: (HomeView) -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    var langDropdownExpanded by remember { mutableStateOf(false) }

    SettingsSectionTitle(s.settingsSectionUI)

    // Language picker
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(s.settingsLanguage, color = CelestiaTheme.colors.textPrimary)
        }

        Box {
            Row(
                Modifier
                    .clickable { langDropdownExpanded = true }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(currentLocale.displayName, color = CelestiaTheme.colors.primary, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
            }

            DropdownMenu(
                expanded         = langDropdownExpanded,
                onDismissRequest = { langDropdownExpanded = false },
                containerColor   = CelestiaTheme.colors.surface
            ) {
                AppLocale.entries.forEach { locale ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                locale.displayName,
                                color      = if (locale == currentLocale) CelestiaTheme.colors.primary
                                else CelestiaTheme.colors.textPrimary,
                                fontWeight = if (locale == currentLocale) FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        onClick = { langDropdownExpanded = false; onLocaleChanged(locale) }
                    )
                    // Puppet: per-locale direct click. Drivers can switch
                    // language without opening the dropdown first -- the
                    // onLocaleChanged callback is what actually mutates
                    // state, the dropdown is presentation only.
                    PuppetClick("settings.language.${locale.name}") {
                        langDropdownExpanded = false
                        onLocaleChanged(locale)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // Theme picker shortcut
    PuppetClick("settings.openThemePicker") { onOpenThemePicker() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .clickable(onClick = onOpenThemePicker)
            .background(CelestiaTheme.colors.primary.copy(alpha = 0.1f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.settingsThemePicker, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(s.settingsThemePickerSub, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
            }
        }
        Icon(Icons.Default.ArrowDropDown, null, tint = CelestiaTheme.colors.primary)
    }

    Spacer(Modifier.height(4.dp))

    // Custom background shortcut
    PuppetClick("settings.openBackground") { onOpenBackgroundSettings() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .clickable(onClick = onOpenBackgroundSettings)
            .background(CelestiaTheme.colors.surface.copy(alpha = 0.4f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wallpaper, null, tint = CelestiaTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.settingsBackground, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(s.settingsBackgroundSub, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = CelestiaTheme.colors.primary)
    }

    Spacer(Modifier.height(4.dp))

    // Dark theme toggle
    var themeSwitchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }
    SettingsSwitchRow(
        title           = s.settingsDarkTheme,
        checked         = themeSwitchState,
        onCheckedChange = { isChecked -> themeSwitchState = isChecked; onToggleTheme() }
    )
    PuppetToggle("settings.darkTheme", themeSwitchState) { isChecked ->
        themeSwitchState = isChecked; onToggleTheme()
    }

    Spacer(Modifier.height(4.dp))

    // Home view variant picker. Lets the user A/B between the legacy
    // Dashboard and the new Library-first surface (currently a
    // placeholder). The toggle persists via settings; the parent
    // updates routing on change.
    HomeViewPicker(
        current      = homeView,
        onChange     = onHomeViewChanged,
        labelTitle   = s.settingsHomeViewTitle,
        labelSub     = s.settingsHomeViewSub,
        classicLabel = s.settingsHomeViewClassic,
        libraryLabel = s.settingsHomeViewLibrary,
    )
    PuppetClick("settings.homeView.classic")      { onHomeViewChanged(HomeView.Classic) }
    PuppetClick("settings.homeView.libraryFirst") { onHomeViewChanged(HomeView.LibraryFirst) }

    Spacer(Modifier.height(4.dp))

    // UI style variant picker. Independent axis from palette -- governs
    // form, surface treatment, motion. Two initial variants: Celestia
    // (current) and Brut (sharp / flat).
    UiStylePicker(
        current  = uiStyle,
        onChange = onUiStyleChanged,
        title    = s.settingsUiStyleTitle,
        sub      = s.settingsUiStyleSub,
        celestia = s.settingsUiStyleCelestia,
        brut     = s.settingsUiStyleBrut,
    )
    PuppetClick("settings.uiStyle.celestia") { onUiStyleChanged(UiStyle.Celestia) }
    PuppetClick("settings.uiStyle.brut")     { onUiStyleChanged(UiStyle.Brut) }

    Spacer(Modifier.height(8.dp))

    // ── Behavior subsection ──────────────────────────────────────────
    SettingsSectionTitle(s.settingsSectionBehavior)
    SettingsSwitchRow(
        title           = s.settingsCloseAfterLaunch,
        checked         = form.closeAfterStart,
        onCheckedChange = { form.closeAfterStart = it; save() }
    )
    PuppetToggle("settings.closeAfterStart", form.closeAfterStart) { form.closeAfterStart = it; save() }

    Spacer(Modifier.height(4.dp))

    // Offline Mode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(
                if (form.isOfflineMode) CelestiaTheme.colors.error.copy(alpha = 0.08f)
                else settingsRowBackground()
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                Icons.Default.WifiOff,
                null,
                tint = if (form.isOfflineMode) CelestiaTheme.colors.error else CelestiaTheme.colors.textSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    s.settingsOfflineMode,
                    color = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    s.settingsOfflineModeDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary
                )
            }
        }
        Switch(
            checked = form.isOfflineMode,
            onCheckedChange = { form.isOfflineMode = it; save() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.error,
                checkedTrackColor = CelestiaTheme.colors.error.copy(alpha = 0.5f)
            )
        )
        PuppetToggle("settings.offlineMode", form.isOfflineMode) { form.isOfflineMode = it; save() }
    }
}
