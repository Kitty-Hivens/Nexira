package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.NxTheme
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
    onOpenCustomizationExtension: () -> Unit,
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
            Symbol(NxIcon.Language, null, tint = NxTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(s.settingsLanguage, color = NxTheme.colors.textPrimary)
        }

        Box {
            Row(
                Modifier
                    .clickable { langDropdownExpanded = true }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(currentLocale.displayName, color = NxTheme.colors.primary, fontWeight = FontWeight.Bold)
                Symbol(NxIcon.ArrowDropDown, null, tint = NxTheme.colors.primary)
            }

            DropdownMenu(
                expanded         = langDropdownExpanded,
                onDismissRequest = { langDropdownExpanded = false },
                containerColor   = NxTheme.colors.surface
            ) {
                AppLocale.entries.forEach { locale ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                locale.displayName,
                                color      = if (locale == currentLocale) NxTheme.colors.primary
                                else NxTheme.colors.textPrimary,
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
            .background(NxTheme.colors.primary.copy(alpha = 0.1f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(NxIcon.Star, null, tint = NxTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.settingsThemePicker, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(s.settingsThemePickerSub, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
        }
        Symbol(NxIcon.ArrowDropDown, null, tint = NxTheme.colors.primary)
    }

    Spacer(Modifier.height(4.dp))

    // Custom background shortcut
    PuppetClick("settings.openBackground") { onOpenBackgroundSettings() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .clickable(onClick = onOpenBackgroundSettings)
            .background(glassSurfaceAlpha(0.4f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(NxIcon.Wallpaper, null, tint = NxTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.settingsBackground, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(s.settingsBackgroundSub, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
        }
        Symbol(NxIcon.ChevronRight, null, tint = NxTheme.colors.primary)
    }

    Spacer(Modifier.height(4.dp))

    // Customization extension shortcut
    PuppetClick("settings.openCustomizationExtension") { onOpenCustomizationExtension() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .clickable(onClick = onOpenCustomizationExtension)
            .background(glassSurfaceAlpha(0.4f))
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(NxIcon.Tune, null, tint = NxTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.settingsCustomizationExt, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(s.settingsCustomizationExtSub, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
        }
        Symbol(NxIcon.ChevronRight, null, tint = NxTheme.colors.primary)
    }

    Spacer(Modifier.height(4.dp))

    // Dark theme toggle
    var themeSwitchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }
    ThemeToggleCard(
        checked         = themeSwitchState,
        title           = s.settingsDarkTheme,
        description     = s.settingsDarkThemeDesc,
        onCheckedChange = { isChecked -> themeSwitchState = isChecked; onToggleTheme() },
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
        newLabel     = s.settingsHomeViewNew,
    )
    PuppetClick("settings.homeView.classic")      { onHomeViewChanged(HomeView.Classic) }
    PuppetClick("settings.homeView.libraryFirst") { onHomeViewChanged(HomeView.LibraryFirst) }
    PuppetClick("settings.homeView.new")          { onHomeViewChanged(HomeView.New) }

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
    SettingsRowWithDescription(
        title           = s.settingsCloseAfterLaunch,
        description     = s.settingsCloseAfterLaunchDesc,
        icon            = NxIcon.MoveToInbox,
        iconTint        = NxTheme.colors.textSecondary,
        checked         = form.closeAfterStart,
        enabled         = true,
        onCheckedChange = { form.closeAfterStart = it; save() }
    )
    PuppetToggle("settings.closeAfterStart", form.closeAfterStart) { form.closeAfterStart = it; save() }

    Spacer(Modifier.height(4.dp))

    // Offline Mode -- the reference rich-toggle card; dark theme above now shares it.
    SettingsToggleCard(
        icon            = NxIcon.WifiOff,
        title           = s.settingsOfflineMode,
        description     = s.settingsOfflineModeDesc,
        checked         = form.isOfflineMode,
        onCheckedChange = { form.isOfflineMode = it; save() },
        accent          = NxTheme.colors.error,
    )
    PuppetToggle("settings.offlineMode", form.isOfflineMode) { form.isOfflineMode = it; save() }
}
