package hivens.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.chrome.LocalCustomChromeAvailable
import hivens.ui.i18n.nexiraStrings
import hivens.ui.platform.rememberHostActions
import hivens.i18n.api.LanguageInfo
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxChoiceItem
import hivens.ui.nx.NxChoiceMenu
import hivens.ui.nx.NxMenuAlign
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.Motion
import hivens.ui.theme.familyForText
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status
import hivens.widget.api.SlotRenderer
import hivens.widget.api.childGap
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Interface + Behavior block. Drives anything user-facing about how the
 * launcher looks and how it behaves around launches: language, background
 * shortcut, window chrome, close-after-launch, offline mode.
 *
 * Two [NxSection] planes (Interface, Behavior) per the island model;
 * expressiveness stays as a row state (the offline accent), never a
 * whole-row wash.
 *
 * The way to the theme picker and the day and night switch belong to the
 * colour provider's controls, which the look draws wherever this leaves the
 * slots of [APPEARANCE_SURFACE] for them, and nothing at all where they are
 * absent or their provider does not draw.
 *
 * Network bypasses live separately in [NetworkSection]; update, content,
 * launch and protocol knobs in [AdvancedSection].
 */
@Composable
internal fun AppearanceSection(
    form: SettingsFormState,
    save: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    currentLanguage: LanguageInfo,
    languages: List<LanguageInfo>,
    onLanguageChanged: (tag: String) -> Unit,
) {
    val s = nexiraStrings()
    var langExpanded by remember { mutableStateOf(false) }
    var langShown by remember { mutableStateOf(false) }
    val langTurn by animateFloatAsState(if (langExpanded) 180f else 0f, animationSpec = Motion.tap, label = "langChevron")

    // The rows take their gap themselves rather than from the section's spacing, so a slot
    // whose controls are absent takes none. The language row is always there and starts the
    // section, so it takes none either.
    val gap = Modifier.childGap(INTERFACE_ROW_GAP, Orientation.Vertical)
    NxSection(s.text("settingsSectionUI"), spacing = 0.dp) {
        // Language. The whole row opens the picker; the trailing shows the
        // current language and hosts the menu. A PuppetClick per language, by tag,
        // lets a driver switch without opening the menu first.
        NxRow(
            title    = s.text("settingsLanguage"),
            icon     = NxIcon.Language,
            iconTint = NxColor.lead(),
            onClick  = { langExpanded = true },
            highlighted = langShown,
            trailing = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The picker lists every language under its own name, so a row can carry a
                        // script the UI face has no glyphs for even while the interface is Latin.
                        Text(
                            currentLanguage.nativeName,
                            color = NxColor.lead(),
                            fontWeight = FontWeight.Bold,
                            fontFamily = familyForText(currentLanguage.nativeName),
                        )
                        Symbol(NxIcon.ExpandMore, null, tint = NxColor.lead(), modifier = Modifier.rotate(langTurn))
                    }
                    NxChoiceMenu(
                        expanded         = langExpanded,
                        onDismissRequest = { langExpanded = false },
                        align            = NxMenuAlign.End,
                        onShownChange    = { langShown = it },
                    ) {
                        languages.forEach { language ->
                            NxChoiceItem(
                                label    = language.nativeName,
                                selected = language.tag == currentLanguage.tag,
                                onClick  = { langExpanded = false; onLanguageChanged(language.tag) },
                            )
                            PuppetClick("settings.language.${language.tag}") {
                                langExpanded = false; onLanguageChanged(language.tag)
                            }
                        }
                    }
                }
            },
        )

        SlotRenderer(SurfaceId(APPEARANCE_SURFACE), SlotId("theme.link"), gap)

        // Custom background shortcut.
        PuppetClick("settings.openBackground") { onOpenBackgroundSettings() }
        NxRow(
            modifier = gap,
            title    = s.text("settingsBackground"),
            subtitle = s.text("settingsBackgroundSub"),
            icon     = NxIcon.Wallpaper,
            iconTint = NxColor.lead(),
            onClick  = onOpenBackgroundSettings,
            trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxColor.lead()) },
        )

        SlotRenderer(SurfaceId(APPEARANCE_SURFACE), SlotId("theme.daynight"), gap)

        // Window chrome. `undecorated` is fixed when the window is created, so the flip
        // lands at the next launch and the row says so rather than looking inert. Where
        // the window manager keeps the frame to itself nothing downstream of the flag is
        // active -- the frame stays OS-decorated, the caption buttons and drag area stand
        // down regardless -- so the row is disabled there instead of offering a switch
        // that changes nothing.
        val host = rememberHostActions()
        val customChromeAvailable = LocalCustomChromeAvailable.current && host.customChromeSupported
        NxToggle(
            label       = s.text("settingsCustomChrome"),
            checked     = shownChecked(form.useCustomChrome, customChromeAvailable),
            modifier    = gap,
            description = s.text("settingsCustomChromeDesc"),
            icon        = NxIcon.Computer,
            enabled     = customChromeAvailable,
        ) { form.useCustomChrome = it; save() }
        if (!customChromeAvailable) {
            Text(
                modifier = gap,
                text  = if (host.customChromeSupported) s.text("settingsCustomChromeTiling") else s.text("settingsUnsupportedHere"),
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
        PuppetToggle("settings.useCustomChrome", shownChecked(form.useCustomChrome, customChromeAvailable), enabled = customChromeAvailable) {
            form.useCustomChrome = it; save()
        }
    }

    Spacer(Modifier.height(16.dp))

    NxSection(s.text("settingsSectionBehavior")) {
        val host = rememberHostActions()
        NxToggle(
            s.text("settingsCloseAfterLaunch"), shownChecked(form.closeAfterStart, host.hideAfterLaunchSupported),
            description = s.text("settingsCloseAfterLaunchDesc"), icon = NxIcon.MoveToInbox,
            enabled = host.hideAfterLaunchSupported,
        ) {
            form.closeAfterStart = it; save()
        }
        if (!host.hideAfterLaunchSupported) {
            Text(text = s.text("settingsUnsupportedHere"), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
        }
        PuppetToggle("settings.closeAfterStart", shownChecked(form.closeAfterStart, host.hideAfterLaunchSupported), enabled = host.hideAfterLaunchSupported) { form.closeAfterStart = it; save() }

        NxToggle(s.text("settingsOfflineMode"), form.isOfflineMode, description = s.text("settingsOfflineModeDesc"), icon = NxIcon.WifiOff, accent = NxColor.status(Status.Error)) {
            form.isOfflineMode = it; save()
        }
        PuppetToggle("settings.offlineMode", form.isOfflineMode) { form.isOfflineMode = it; save() }
    }
}

/**
 * What the switch of a setting shows: the stored value where the setting can act,
 * off where it cannot. A switch drawn on over "not supported on this device" claimed
 * a title bar a phone never draws. The stored value is left as it is, so the setting
 * comes back as it was on a host that has it. The automation hook reads the same,
 * as every other toggle's reports what its switch shows.
 */
internal fun shownChecked(stored: Boolean, available: Boolean): Boolean = stored && available

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

/** The surface whose slots this section leaves for the colour provider's controls. */
private const val APPEARANCE_SURFACE = "settings.appearance"

/** The gap between the rows of the Interface section, the section's own default spacing. */
private val INTERFACE_ROW_GAP = 12.dp
