package hivens.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.chrome.IS_TILING_WM
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxChoiceItem
import hivens.ui.nx.NxChoiceMenu
import hivens.ui.nx.NxMenuAlign
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxSwitch
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.customization.LocalCustomization
import hivens.ui.theme.LocalThemeReveal
import hivens.ui.theme.Motion
import hivens.ui.theme.familyForText
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * Interface + Behavior block. Drives anything user-facing about how the
 * launcher looks and how it behaves around launches: language, theme
 * preset shortcut, background shortcut, dark/light toggle, window chrome,
 * close-after-launch, offline mode.
 *
 * Two [NxSection] planes (Interface, Behavior) per the island model;
 * expressiveness stays as a row state (day/night sun/moon + reveal,
 * the offline accent), never a whole-row wash.
 *
 * Network bypasses live separately in [NetworkSection]; update, content,
 * launch and protocol knobs in [AdvancedSection].
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
) {
    val s = LocalStrings.current
    var langExpanded by remember { mutableStateOf(false) }
    var langShown by remember { mutableStateOf(false) }
    val langTurn by animateFloatAsState(if (langExpanded) 180f else 0f, animationSpec = Motion.tap, label = "langChevron")
    var themeSwitchState by remember(isDarkTheme) { mutableStateOf(isDarkTheme) }

    NxSection(s.settingsSectionUI) {
        // Language. The whole row opens the picker; the trailing shows the
        // current locale and hosts the menu. Per-locale PuppetClick lets a driver
        // switch without opening the menu first.
        NxRow(
            title    = s.settingsLanguage,
            icon     = NxIcon.Language,
            iconTint = NxColor.lead(),
            onClick  = { langExpanded = true },
            highlighted = langShown,
            trailing = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The picker lists every locale under its own name, so a row can carry a
                        // script the UI face has no glyphs for even while the interface is Latin.
                        Text(
                            currentLocale.displayName,
                            color = NxColor.lead(),
                            fontWeight = FontWeight.Bold,
                            fontFamily = familyForText(currentLocale.displayName),
                        )
                        Symbol(NxIcon.ExpandMore, null, tint = NxColor.lead(), modifier = Modifier.rotate(langTurn))
                    }
                    NxChoiceMenu(
                        expanded         = langExpanded,
                        onDismissRequest = { langExpanded = false },
                        align            = NxMenuAlign.End,
                        onShownChange    = { langShown = it },
                    ) {
                        AppLocale.entries.forEach { locale ->
                            NxChoiceItem(
                                label    = locale.displayName,
                                selected = locale == currentLocale,
                                onClick  = { langExpanded = false; onLocaleChanged(locale) },
                            )
                            PuppetClick("settings.language.${locale.name}") {
                                langExpanded = false; onLocaleChanged(locale)
                            }
                        }
                    }
                }
            },
        )

        // Theme preset shortcut.
        PuppetClick("settings.openThemePicker") { onOpenThemePicker() }
        NxRow(
            title    = s.settingsThemePicker,
            subtitle = s.settingsThemePickerSub,
            icon     = NxIcon.Star,
            iconTint = NxColor.lead(),
            onClick  = onOpenThemePicker,
            trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxColor.lead()) },
        )

        // Custom background shortcut.
        PuppetClick("settings.openBackground") { onOpenBackgroundSettings() }
        NxRow(
            title    = s.settingsBackground,
            subtitle = s.settingsBackgroundSub,
            icon     = NxIcon.Wallpaper,
            iconTint = NxColor.lead(),
            onClick  = onOpenBackgroundSettings,
            trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxColor.lead()) },
        )

        // Dark theme: day/night identity (sun warm, moon cool) + GNOME-style reveal.
        DayNightRow(
            checked     = themeSwitchState,
            title       = s.settingsDarkTheme,
            description = s.settingsDarkThemeDesc,
        ) { isChecked -> themeSwitchState = isChecked; onToggleTheme() }
        PuppetToggle("settings.darkTheme", themeSwitchState) { isChecked ->
            themeSwitchState = isChecked; onToggleTheme()
        }

        // Window chrome. `undecorated` is fixed when the window is created, so the flip
        // lands at the next launch and the row says so rather than looking inert. On a
        // tiling WM nothing downstream of the flag is active -- the frame stays
        // OS-decorated, the caption buttons and drag area stand down regardless -- so
        // the row is disabled there instead of offering a switch that changes nothing.
        NxToggle(
            label       = s.settingsCustomChrome,
            checked     = form.useCustomChrome,
            description = s.settingsCustomChromeDesc,
            icon        = NxIcon.Computer,
            enabled     = !IS_TILING_WM,
        ) { form.useCustomChrome = it; save() }
        if (IS_TILING_WM) {
            Text(
                text  = s.settingsCustomChromeTiling,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
        PuppetToggle("settings.useCustomChrome", form.useCustomChrome, enabled = !IS_TILING_WM) {
            form.useCustomChrome = it; save()
        }
    }

    Spacer(Modifier.height(16.dp))

    NxSection(s.settingsSectionBehavior) {
        NxToggle(s.settingsCloseAfterLaunch, form.closeAfterStart, description = s.settingsCloseAfterLaunchDesc, icon = NxIcon.MoveToInbox) {
            form.closeAfterStart = it; save()
        }
        PuppetToggle("settings.closeAfterStart", form.closeAfterStart) { form.closeAfterStart = it; save() }

        NxToggle(s.settingsOfflineMode, form.isOfflineMode, description = s.settingsOfflineModeDesc, icon = NxIcon.WifiOff, accent = NxColor.status(Status.Error)) {
            form.isOfflineMode = it; save()
        }
        PuppetToggle("settings.offlineMode", form.isOfflineMode) { form.isOfflineMode = it; save() }
    }
}

// Static day/night colours -- the sun stays warm-orange and the moon cool-blue
// regardless of palette, so the dark-theme toggle reads as day/night at a glance.
private val SunOrange = Color(0xFFFF8C00)
private val MoonBlue  = Color(0xFF8AB4F8)

/**
 * Dark-theme toggle as an in-plane row with a day/night identity: sun (warm) when
 * light, moon (cool) when dark. Icon and switch track take that FIXED colour, not
 * the theme's lead colour. The flip runs through the GNOME-style reveal (a circle growing
 * out of the switch) when a host is present; no host (or motion off) is a plain flip.
 */
@Composable
internal fun DayNightRow(
    checked: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tint = if (checked) MoonBlue else SunOrange
    val reveal = LocalThemeReveal.current
    // The theme wipe's own pace -- a set piece rather than an interface response.
    // With motion reduced it is no wipe at all: the reveal reads a non-positive
    // duration as a plain flip.
    val still = LocalCustomization.current.reduceMotion
    val durationMs = if (still) 0 else Motion.ownRhythm(THEME_REVEAL_MS).durationMs
    var switchOrigin by remember { mutableStateOf(Offset.Zero) }
    val onToggle: (Boolean) -> Unit = { newValue ->
        if (reveal != null) reveal.reveal(switchOrigin, durationMs) { onCheckedChange(newValue) }
        else onCheckedChange(newValue)
    }
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Symbol(if (checked) NxIcon.DarkMode else NxIcon.LightMode, null, tint = tint, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = NxInk.main, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
            }
        }
        Spacer(Modifier.width(12.dp))
        NxSwitch(
            checked         = checked,
            onCheckedChange = onToggle,
            accent          = tint,
            modifier        = Modifier.onGloballyPositioned { switchOrigin = it.boundsInWindow().center },
        )
    }
}

/**
 * A labelled single-select chip group (title + sub + wrapping [NxChoiceChip]s). The
 * chips wrap to a second line on a narrow pane instead of the longest label shrinking
 * per character. Shared across the settings sections so every enum choice reads the
 * same.
 */
@Composable
internal fun PickerBlock(
    title: String,
    sub: String,
    chips: @Composable FlowRowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = NxInk.main, fontWeight = FontWeight.Medium)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            content               = chips,
        )
    }
}

/** How long the theme wipe takes to cross the window. */
private const val THEME_REVEAL_MS = 550
